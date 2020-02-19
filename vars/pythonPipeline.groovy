// ADD THE FOLLOWING TO YOUR JENKINS FILE TO USE THIS PIPELINE:
// @Library('thespacedoctor')_
// pythonPipeline { 
//     myParam = 'someVal' 
//     myParam2 = 'someVal2'
// }



def call(body) {
    // EVALUATE THE BODY BLOCK, AND COLLECT CONFIGURATION INTO THE OBJECT
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {

        agent any

        // CHECK GITHUB EVERY 5 MINS MONDAY-FRIDAY
        // triggers {
        //     pollSCM('*/5 * * * 1-5')
        // }

        options {
            // skipDefaultCheckout(true)
            // KEEP THE 10 MOST RECENT BUILDS
            buildDiscarder(logRotator(numToKeepStr: '10'))
            timestamps()
        }
        // SOURCE ANACONDA
        environment {
          PATH="/var/lib/jenkins/anaconda/bin:$PATH"
          REPO_NAME=repoName()
          OVERVIEW_URL=activityUrl()
          BUILD_URL=buildUrl()
        }

        stages {
            stage ("Code pull"){
                steps{
                    checkout scm
                }
            }

            stage('Build conda python 3.7 environment & install code') {
                steps {
                    sh '''conda create --yes -n ${BUILD_TAG}-p3 python=3.7 pip
                          source activate ${BUILD_TAG}-p3 
                          conda install pytest coverage pytest-cov
                          pip install coverage-badge
                          python setup.py install
                        '''
                }
            }
            stage('Build conda python 2.7 environment & install code') {
                steps {
                    sh '''conda create --yes -n ${BUILD_TAG}-p2 python=2.7 pip
                          source activate ${BUILD_TAG}-p2
                          conda install pytest pandas coverage pytest-cov 
                          pip install coverage-badge
                          python setup.py install
                        '''
                }
            }
            stage('Unit tests for Python 3') {
                steps {
                    sh  ''' source activate ${BUILD_TAG}-p3
                            pytest --verbose --junit-xml test-reports/unit_tests_p3.xml --cov --cov-report xml:reports/coverage.xml --cov-report html
                            coverage-badge -f -o coverage.svg
                        '''
                }
                post {
                    always {
                        // Archive unit tests for the future
                        junit allowEmptyResults: true, testResults: 'test-reports/unit_tests_p3.xml'
                    }
                }
            }
            stage('Unit tests for Python 2') {
                steps {
                    sh  ''' source activate ${BUILD_TAG}-p2
                            pytest --verbose --junit-xml test-reports/unit_tests_p2.xml
                        '''
                }
                post {
                    always {
                        // Archive unit tests for the future
                        junit allowEmptyResults: true, testResults: 'test-reports/unit_tests_p2.xml'
                    }
                }
            }
            stage('Convert Coverage Reports for Jenkins') {
                steps {
                    sh  ''' source activate ${BUILD_TAG}-p3
                        '''
                }
                post{
                always{
                    step([$class: 'CoberturaPublisher',
                                   autoUpdateHealth: false,
                                   autoUpdateStability: false,
                                   coberturaReportFile: 'reports/coverage.xml',
                                   failNoReports: false,
                                   failUnhealthy: false,
                                   failUnstable: false,
                                   maxNumberOfBuilds: 10,
                                   onlyStable: false,
                                   sourceEncoding: 'ASCII',
                                   zoomCoverageChart: false])
                    }
                }
            }
            
        }
        post {
            // http://167.99.90.204:8080/blue/organizations/jenkins/xxxpython_package_namexxx/detail/feature%2Fadding-jenkins-pipeline/12/pipeline
            // http://167.99.90.204:8080/blue/organizations/jenkins/xxxpython_package_namexxx/feature%2Fadding-jenkins-pipeline/12/pipeline/
            // http://167.99.90.204:8080/blue/organizations/jenkins/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline
            // URL ENCODE BRANCH PLEASE: ${env.JENKINS_URL}/blue/organizations/jenkins/${git_repo_name}/${git_branch_name}/${env.BUILD_NUMBER}/pipeline
            always {
                slackSend message: slackMessage("Finished Successfully")
                sh 'conda remove --yes -n ${BUILD_TAG}-p3 --all'
                sh 'conda remove --yes -n ${BUILD_TAG}-p2 --all'
            }
            failure {
                slackSend message: slackMessage("Failed")
            }
        }
    }
}


String branchName() {
    return scm.getUserRemoteConfigs()[0].getUrl()
}
String repoName() {
    return scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0]
}
String activityUrl() {
    rn = repoName()
    return "${env.JENKINS_URL}/blue/organizations/jenkins/${rn}/activity"
}
String buildUrl() {
    rn = repoName()
    bn = "${env.BRANCH_NAME}".replaceAll("/","%2F")
    return "${env.JENKINS_URL}/blue/organizations/jenkins/${rn}/detail/${bn}/${env.BUILD_NUMBER}/pipeline"
}
String slackMessage(status) {
    return "<${env.OVERVIEW_URL}|${env.REPO_NAME}> / <${env.BUILD_URL}|${env.BRANCH_NAME}>\n\tBuild ${env.BUILD_NUMBER} ${status}"
}
