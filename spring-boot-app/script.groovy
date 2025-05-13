def pullSourceCode() {
    echo "====++++Pulling Source Code From Repo++++===="
    checkout([$class: 'GitSCM',
        branches: [[name: "*/${params.BRANCH_NAME}"]],
        doGenerateSubmoduleConfigurations: false,
        extensions: [
            [$class: 'PruneStaleBranch'],
            [$class: 'CleanBeforeCheckout'],
            [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true] // depth could be parametized for larger repos
        ],
        userRemoteConfigs: [[credentialsId: env.GIT_CREDS, url: env.GIT_URL]]
    ])
}

def secretsScan() {
    echo "====++++Running Gitleaks for Secrets Scanning++++===="
    sh """
        mkdir -p gitleaks-reports
        gitleaks detect --source spring-boot-app \
        --report-format sarif \
        --report-path gitleaks-reports/gitleaks-report.sarif
    """
    archiveArtifacts artifacts: 'gitleaks-reports/**', fingerprint: true

    def result = sh(script: "gitleaks detect --source spring-boot-app --exit-code 1", returnStatus: true)
    if (result != 0 && params.FAIL_ON_LEAKS) {
        error("Secrets detected by Gitleaks!!")
        echo "Failing the build! Secrets detected in source code."
    } else {
        echo "No secrets detected by Gitleaks."
    }
}

def buildMavenProject() {
    echo "====++++Building Maven Project++++===="
    dir('spring-boot-app') {
       sh 'mvn clean package -DskipTests'
       sh 'ls -lrt target/'
    }
}

def deployToNexus() {
    echo "====++++Deploying To Nexus++++===="
    dir('spring-boot-app') {
        withMaven(globalMavenSettingsConfig: 'settings', jdk: '', maven: 'maven-3.9.9', mavenSettingsConfig: '', traceability: true) {
            sh 'mvn deploy'
        }
    }
}

def sonarCloudAnalysis() {
    echo "====++++Sonar Code Quality++++===="
    withSonarQubeEnv('SonarCloud') {
        script {
            def SCANNER_HOME = tool name: 'SonarScanner', type: 'hudson.plugins.sonar.SonarRunnerInstallation'
            try {
                sh """
                    ${SCANNER_HOME}/bin/sonar-scanner \
                    -Dsonar.organization=$SONAR_ORG \
                    -Dsonar.projectKey=$SONAR_PROJECT_KEY \
                    -Dsonar.projectVersion=${params.PROJECT_VERSION} \
                    -Dsonar.java.binaries=spring-boot-app/target
                """
            } catch (Exception e) {
                error "Unexpected error during SonarQube analysis: ${e.getMessage()}"
            }
        }
    }
}

def checkDockerfile() {
    echo "====++++Checking Dockerfile++++===="
        if (!fileExists(env.DOCKERFILE)) {
            error "Dockerfile does not exist at path: ${env.DOCKERFILE}"
        }
        echo "Dockerfile exists at path: ${env.DOCKERFILE}"
        def dockerfileContent = readFile(env.DOCKERFILE)
        if (dockerfileContent.contains('FROM scratch')) {
            error "Dockerfile contains 'FROM scratch'. Please use a valid base image."
        }
    echo "Dockerfile is valid and does not contain 'FROM scratch'."
}

def buildDockerImage() {
    echo "====++++Building Docker Image++++===="
    sh """
        docker build \
        --no-cache \
        --pull \
        -t ${env.IMAGE_NAME} \
        -f ${env.DOCKERFILE} .
    """
}

def trivyScan() {
    echo "====++++Running Trivy Scan++++===="
        // Define directories and file paths
        def reportDir = "trivy-reports"

        // Create necessary directories
        echo "=====+++++Setting up directories for Trivy scan++++====="
        sh "mkdir -p ${reportDir} ${env.TRIVY_CACHE_DIR}"

        // Check Trivy version
        echo "====++++ Displaying Trivy version ++++===="
        sh "trivy --version"

        // Download Trivy database with retries in case of failure
        echo "====++++ Downloading Trivy vulnerability database ++++===="
        retry(3) {
            sh """
                set -e
                trivy image --download-db-only
            """
        }

        // Ensure the Trivy HTML template exists
        sh """
            if [ ! -f contrib/html.tpl ]; then
                mkdir -p contrib
                wget -O contrib/html.tpl https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl
            fi
        """

        // Run Trivy scan
        try {
            echo "====++++ Running Trivy scan on Docker image ++++===="
            // Authenticate and perform the scan
            withCredentials([
                usernamePassword(
                    credentialsId: 'gitAuth', // Replace with your GitHub credentials ID as stored in Jenkins
                    usernameVariable: 'GHCR_USERNAME', // Could use hashicorp vault or other secret management tools
                    passwordVariable: 'GHCR_TOKEN'
                )
            ]) {
                env.TRIVY_SCAN_STATUS = sh(
                    script: """
                        set -e
                        trivy image \
                            --exit-code 1 \
                            --cache-dir ${env.TRIVY_CACHE_DIR} \
                            --severity ${params.TRIVY_SEVERITY} \
                            --no-progress \
                            --format table \
                            --output ${reportDir}/trivy-report.html \
                            ${env.IMAGE_NAME}
                    """,
                    returnStatus: true
                ).toString()
            }

            // Consolidated logic to handle the report
            if (fileExists("${reportDir}/trivy-report.html")) {
                archiveArtifacts artifacts: "${reportDir}/trivy-report.html", fingerprint: true
                echo "Trivy report found and archived successfully."
            } else {
                error "Trivy report not generated at ${reportDir}/trivy-report.html. Failing the build."
            }
            // Analyze the scan results based on the exit code
            if (env.TRIVY_SCAN_STATUS.toInteger() == 0) {
                echo "No vulnerabilities found with severity ${params.TRIVY_SEVERITY}."
            } else if (env.TRIVY_SCAN_STATUS.toInteger() == 1) {
                error "Trivy scan found vulnerabilities or encountered issues. Exit code: ${env.TRIVY_SCAN_STATUS}"
            }
        } catch (Exception e) {
            unstable "Trivy scan encountered an error: ${e.getMessage()}"
            echo "Pipeline marked as unstable due to Trivy scan issues."
        }
}

def uploadTrivyReportToS3() {
    echo "====++++ Uploading Trivy Report to S3 ++++===="
        def s3Bucket = env.S3_BUCKET_NAME.replaceAll('s3://', '') // Ensures no duplicate `s3://`
        def s3Key = "trivy-reports/trivy-report.html"
        def s3Url = "s3://${s3Bucket}/${s3Key}" // Full S3 path

        def reportPath = "trivy-reports/trivy-report.html"
        def reportSize = fileExists(reportPath) ? sh(script: "wc -c < ${reportPath}", returnStdout: true).trim().toInteger() : 0

        if (reportSize > 0) {
            try {
                sh "aws s3 cp ${reportPath} ${s3Url}"
                echo "Trivy report uploaded successfully to S3: ${s3Url}"
            } catch (Exception e) {
                error("Failed to upload Trivy report to S3: ${s3Url}. Error: ${e.getMessage()}")
            }
        } else {
            echo "Trivy report not found or empty; skipping upload."
        } 
}

def pushDockerImage() {
    echo "====++++ Pushing Docker Image to Registry ++++===="
    def dockerRegistryUrl = 'https://index.docker.io/v1/' // Here we're using Docker Hub as Image Registry
    withDockerRegistry([credentialsId: 'dockerId', url: dockerRegistryUrl]) {
        retry(3) {
            sh "docker push ${env.IMAGE_NAME}"
       }
        echo "Docker image pushed successfully to ${dockerRegistryUrl}/${env.DOCKER_NAMESPACE}"
    }
}

def smokeTest() {
    echo "====++++ Running Smoke Test ++++===="
        def hostPort = params.HOST_PORT.toInteger()
        try {
            sh """
                docker run --name ${env.CONTAINER_NAME} \
                    -d \
                    -p ${hostPort}:${params.CONTAINER_PORT} \
                    ${env.IMAGE_NAME}
            """
            sh "chmod +x ${WORKSPACE}/spring-boot-app/check.sh"
            sh """
                sleep 30
                APP_PORT=${hostPort} ${WORKSPACE}/spring-boot-app/check.sh
            """
        } catch (Exception e) {
            error("Smoke test failed: ${e.getMessage()}")
        }
}
   
def cleanup() {
    echo "====++++ Cleaning Up Docker Images and Containers ++++===="
    try {
        sh "docker stop ${env.CONTAINER_NAME} 2>&1 > /dev/null || echo 'Container ${env.CONTAINER_NAME} not found.'"
        sh "docker rm -f ${env.CONTAINER_NAME} 2>&1 > /dev/null || echo 'Container ${env.CONTAINER_NAME} not found.'"
        sh "docker rmi -f ${env.IMAGE_NAME} 2>&1 > /dev/null || echo 'Image ${env.IMAGE_NAME} not found.'"
        sh "docker ps -a"
    } catch (Exception e) {
        error("Cleanup failed: ${e.getMessage()}")
    }
}

def getEmailForUsers(String userIdsCsv) {
    def emailMap = [
        'user1': 'samuelhaddison@gmail.com',
        'user2': 'orionhouse83@gmail.com'
    ]
    def userIds = userIdsCsv.split(',').collect { it.trim() }
    def emails = userIds.collect { id ->
        if (!emailMap.containsKey(id)) {
            error("Invalid recipient ID: ${id}")
        }
        return emailMap[id]
    }
    return emails.join(',')
}


return this