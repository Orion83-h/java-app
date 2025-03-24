#!/bin/bash

# ============================
# Purpose: Smoke Test Script
# Author: Samuel Haddison
# ============================

# Default Configuration
APP_PORT=${APP_PORT:-8084}
TEST_ENDPOINT=${TEST_ENDPOINT:-"http://localhost:${APP_PORT}"}
TRIVY_SEVERITY_LEVELS=("LOW" "MEDIUM" "HIGH" "CRITICAL")
TRIVY_SEVERITY_FILE="${WORKSPACE}/trivy-reports/trivy-report.html"
RETRIES=3
WAIT_TIME=5
SUCCESS_COLOR="\033[0;32m"
ERROR_COLOR="\033[0;31m"
RESET_COLOR="\033[0m"

# Function for logging success messages
log_success() {
    echo -e "$(date +'%d-%m-%Y %H:%M:%S') ${SUCCESS_COLOR}✓ $1${RESET_COLOR}"
}

# Function for logging error messages
log_error() {
    echo -e "$(date +'%d-%m-%Y %H:%M:%S') ${ERROR_COLOR}✗ $1${RESET_COLOR}"
}

# Health Check Test
health_check() {
    echo "Performing health check on ${TEST_ENDPOINT}..."

    for ((i = 1; i <= RETRIES; i++)); do
        local http_response=$(curl -is --max-time 10 ${TEST_ENDPOINT} -L)
        if echo "${http_response}" | grep -q "HTTP/1.1 200"; then
            log_success "Application is accessible and responding with 200 OK"
            return 0
        else
            log_error "Attempt $i: Unable to reach application on port ${APP_PORT}"
            sleep ${WAIT_TIME}
        fi
    done

    log_error "Application health check failed after ${RETRIES} retries"
    return 1
}

# Vulnerability Scan Check
vulnerability_check() {
    echo "Checking for vulnerabilities in ${TRIVY_SEVERITY_FILE}..."

    if [[ ! -f "${TRIVY_SEVERITY_FILE}" ]]; then
        log_error "Trivy results file not found: ${TRIVY_SEVERITY_FILE}"
        return 1
    fi

    local has_vulnerabilities=0

    for severity in "${TRIVY_SEVERITY_LEVELS[@]}"; do
        if grep -q "${severity}" "${TRIVY_SEVERITY_FILE}"; then
            log_error "${severity} vulnerabilities found in Docker image"
            has_vulnerabilities=1
        else
            log_success "No ${severity} vulnerabilities detected"
        fi
    done

    if [[ ${has_vulnerabilities} -eq 1 ]]; then
        return 1
    else
        return 0
    fi
}

# Main Smoke Test Execution
main() {
    echo "======================================"
    echo "Starting Smoke Test"
    echo "======================================"

    # Perform health check
    if ! health_check; then
        exit 1
    fi

    # Check vulnerabilities
    if ! vulnerability_check; then
        exit 1
    fi

    echo "======================================"
    log_success "Smoke Test Passed Successfully"
    echo "======================================"
    exit 0
}

# Execute main function
main