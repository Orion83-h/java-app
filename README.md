# Spring Boot CI/CD Pipeline

A robust CI/CD pipeline for automating the build, test, and deployment of a Spring Boot application using Jenkins.

## Pipeline Overview

This Jenkins pipeline automates the entire software delivery process for our Spring Boot application, from source code retrieval to deployment. It implements security scanning, code quality checks, containerization, and automated testing to ensure reliable and secure deployments.

## Pipeline Stages

### 1. Source Code Retrieval
**Stage: Pull Src Code**

Fetches the latest source code from our Git repository based on the specified branch.

```groovy
checkout([$class: 'GitSCM',
    branches: [[name: "*/${params.BRANCH_NAME}"]],
    doGenerateSubmoduleConfigurations: false,
    extensions: [
        [$class: 'PruneStaleBranch'],
        [$class: 'CleanBeforeCheckout'],
        [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]
    ],
    userRemoteConfigs: [[credentialsId: env.GIT_CREDS, url: env.GIT_URL]]
])
```

- Uses shallow cloning for faster checkout
- Cleans workspace before checkout to ensure a fresh start
- Prunes stale branches to maintain a clean repository state

### 2. Security Scanning
**Stage: Secrets Scan (Gitleaks)**

Performs static code analysis to detect potential secrets or sensitive information in the source code.

- Uses Gitleaks to scan for exposed API keys, passwords, and other sensitive data
- Generates SARIF format reports for better compatibility with security tools
- Can be configured to fail the build when secrets are detected (controlled by `FAIL_ON_LEAKS` parameter)

### 3. Application Build
**Stage: Build Maven Project**

Compiles the Spring Boot application and packages it into a deployable artifact.

- Uses Maven for dependency management and build automation
- Skips tests during this phase to optimize build time (tests are run separately)
- Verifies build output by listing the generated artifacts

### 4. Artifact Repository Upload
**Stage: Deploy To Nexus**

Uploads the built artifacts to the Nexus repository for version tracking and storage.

- Uses Maven's deploy goal with custom settings
- Ensures artifacts are properly versioned and accessible for future deployments
- Maintains a history of all builds for traceability

### 5. Code Quality Analysis
**Stage: SonarCloud Analysis**

Analyzes the codebase for quality issues, bugs, vulnerabilities, and code smells.

- Integrates with SonarCloud for detailed static code analysis
- Scans compiled code to ensure accurate results
- Configurable through project properties to adapt to various code quality standards
- Tracks code quality metrics across versions

### 6. Docker Operations
**Stage: Docker Operations (Parallel)**

Runs two parallel tasks to validate and build the Docker image.

**Check Dockerfile:**

- Verifies the Dockerfile exists at the specified path
- Fails gracefully with meaningful error messages

**Build Docker Image:**

- Builds a Docker container based on the application
- Uses no-cache to ensure a clean build
- Pulls the latest base image for security

### 7. Container Security Scanning
**Stage: Trivy Scan**

Scans the built Docker image for vulnerabilities.

- Uses Trivy to identify OS and application vulnerabilities
- Configurable severity levels (LOW, MEDIUM, HIGH, CRITICAL)
- Generates detailed HTML reports
- Can block progression if critical vulnerabilities are found

### 8. Report Storage
**Stage: Upload Trivy Report to S3**

Uploads security scan reports to S3 for long-term storage and compliance.

- Maintains a history of vulnerability scans
- Enables security audit trails
- Persists reports beyond the Jenkins job history

### 9. Image Publishing
**Stage: Push Docker Image**

Pushes the Docker image to the registry if it passes security scanning.

- Conditional execution based on Trivy scan results
- Uses credentials for secure registry authentication
- Implements retries for network resilience
- Only publishes verified secure images

### 10. Smoke Testing
**Stage: Smoke Test**

Conducts basic functionality tests on the deployed application.

- Spins up a container from the built image
- Exposes the application on the configured port
- Runs a health check script to verify application is running correctly
- Validates the deployment before proceeding

### 11. Cleanup
**Stage: Cleanup**

Cleans up resources used during the build process.

- Stops and removes test containers
- Removes Docker images to free up disk space
- Ensures a clean state for subsequent builds

## Post-Build Actions

### Success Actions
- Triggers downstream Helm chart deployment job
- Sends success email notification with detailed build information
- Includes links to vulnerability reports

### Failure Actions
- Sends failure email notification with error details
- Includes links to logs and reports for troubleshooting

### Always Actions
- Cleans up the workspace to prevent disk space issues
- Ensures Jenkins resources are properly managed

## Pipeline Parameters

| Parameter           | Description                          | Default            |
|---------------------|--------------------------------------|--------------------|
| `BUILD_NUM_TO_KEEP` | Number of builds to retain           | 2                  |
| `BUILD_DAYS_TO_KEEP`| Discard builds older than specified days | 7              |
| `CONTAINER_PORT`    | Port to expose within the container  | 8084               |
| `BRANCH_NAME`       | Git branch to build                  | main, dev, staging |
| `MAIL_TO`           | Email recipients                     | Configurable list  |
| `PROJECT_VERSION`   | Version of the project to build      | 1.0, 1.1, 1.2      |
| `ENVIRONMENT`       | Environment to deploy to             | dev, test, prod    |
| `TRIVY_SEVERITY`    | Severity level for vulnerability scanning | LOW, MEDIUM, HIGH, CRITICAL |
| `HOST_PORT`         | Host port for smoke testing          | 8084               |
| `FAIL_ON_LEAKS`     | Fail the build if secrets are found  | true               |
| `FAIL_ON_ISSUES`    | Fail if critical vulnerabilities are found | true           |

## Pipeline Configuration

The pipeline leverages several Jenkins features for optimal performance:

- **Build Discarder**: Automatically manages workspace and log file storage
- **Quiet Period**: Prevents multiple builds from triggering in rapid succession
- **Timestamps**: Adds timestamps to console output for better debugging
- **Parallel Execution**: Optimizes build time by running independent tasks simultaneously

## Required Jenkins Plugins

- Git Plugin
- Maven Integration
- Pipeline Stage View
- SonarQube Scanner
- Docker Pipeline
- Email Extension
- AWS Steps
- Workspace Cleanup

## Required Tools/Integrations

- Maven 3.x
- Docker
- Gitleaks
- Trivy
- AWS CLI
- SonarCloud Account
- Nexus Repository Manager
- Docker Registry (Docker Hub)

## Usage

1. Configure a Jenkins pipeline job pointing to your repository
2. Set up the required credentials in Jenkins:
    - `gitAuth` - Git repository credentials
    - `dockerId` - Docker registry credentials
3. Configure the SonarCloud integration in Jenkins
4. Set up notification email addresses
5. Run the pipeline!

> **Note:** This pipeline is designed to be configurable through parameters. Modify parameter values in the Jenkins UI to adjust behavior for different environments and requirements.
