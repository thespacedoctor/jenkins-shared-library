// ADD THE FOLLOWING TO YOUR JENKINS FILE TO USE THIS PIPELINE:
// @Library('thespacedoctor')_
// pythonPipeline { 
//     myParam = 'someVal' 
//     myParam2 = 'someVal2'
// }

import java.lang.Math;

def call(body) {
    // EVALUATE THE BODY BLOCK, AND COLLECT CONFIGURATION INTO THE OBJECT
    def pipelineParams= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()
    def buildBadge = addEmbeddableBadgeConfiguration()

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
          BRANCH_NAME=branchName()
          REPO_NAME=repoName()
          OVERVIEW_URL=activityUrl()
          BUILD_URL=buildUrl()
          COVERAGE_URL=coverageReportUrl()
          BRANCH_MATCH=branchName2()
        }

        stages {
            stage ("Code pull") {
                steps{
                    script {
                        slackSend(blocks: slackMessage('running'))
                        buildBadge.setStatus('running')
                    }
                    checkout scm
                }
            }

            stage('Build conda python 2.7 environment & install code') {
                when {
                    expression {
                        PYTHON2 == '' || PYTHON2 == true
                    }
                }
                steps {
                    sh '''conda create --yes -n ${BUILD_TAG}-p2 python=2.7 pip
                                  source activate ${BUILD_TAG}-p2
                                  conda install pytest pandas coverage pytest-cov ${EXTRA_CONDA_PACKAGES}
                                  ${EXTRA_CONDA_INSTALL_COMMANDS}
                                  pip install coverage-badge ${EXTRA_PIP_PACKAGES}
                                  python setup.py install
                                '''
                }
            }

            stage('Build conda python 3.7 environment & install code') {
                steps {
                    sh '''conda create --yes -n ${BUILD_TAG}-p3 python=3.7 pip twine
                          source activate ${BUILD_TAG}-p3 
                          conda install pytest coverage pytest-cov ${EXTRA_CONDA_PACKAGES}
                          ${EXTRA_CONDA_INSTALL_COMMANDS}
                          pip install coverage-badge ${EXTRA_PIP_PACKAGES}
                          python setup.py install
                        '''
                }
            }
        
            stage('Build Docs in Python 3') {
                steps {
                    sh  ''' source activate ${BUILD_TAG}-p3
                            cd docs
                            pip install -r requirements.txt
                            make html
                        '''
                }
            }

            stage('Unit tests for Python 2') {
                when {
                    expression {
                        PYTHON2 == '' || PYTHON2 == true
                    }
                }
                steps {
                    script {
                        try {
                            sh  ''' source activate ${BUILD_TAG}-p2
                                    pytest --verbose --junit-xml test-reports/unit_tests_p2.xml
                                '''
                            buildBadge.setStatus('passing')
                        } catch (Exception err) {
                            buildBadge.setStatus('failing')
                        }
                    }
                }
                post {
                    always {
                        // Archive unit tests for the future
                        junit allowEmptyResults: true, testResults: 'test-reports/unit_tests_p2.xml'
                    }
                }
            }

            stage('Unit tests for Python 3') {
                steps {
                    script {
                        try {
                            sh  ''' source activate ${BUILD_TAG}-p3
                                    pytest --verbose --junit-xml test-reports/unit_tests_p3.xml --cov --cov-report xml:reports/coverage.xml 
                                    coverage-badge -f -o coverage.svg
                                    which head
                                    which grep
                                    head -3 reports/coverage.xml | grep -oP "line-rate\\S*" | grep -oP "\\d.\\d*" > reports/coverage.txt
                                '''
                        } catch (Exception err) {
                            buildBadge.setStatus('failing')
                        }
                    }
                }
                post {
                    always {
                        // Archive unit tests for the future
                        junit allowEmptyResults: true, testResults: 'test-reports/unit_tests_p3.xml'
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

            stage('Merge Hotfix/Feature to Development Branch') {
                when {
                    expression {
                        currentBuild.currentResult == 'SUCCESS' && (BRANCH_MATCH ==~ /feature.*/ || BRANCH_MATCH ==~ /hotfix.*/)
                    }
                }
                steps {
                    sshagent (credentials: ['jenkins-generated-ssh-key']) {
                        sh '''git config core.sshCommand "ssh -v -o StrictHostKeyChecking=no"
                              git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
                              git fetch --all
                              git commit -am "adding files generated during build" || true
                              git branch -a
                              git checkout develop
                              git merge ${BRANCH_MATCH}
                              git commit -am "Merged ${BRANCH_MATCH} branch to develop" || true
                              git push origin develop
                           '''
                    }
                }
            }

            stage('Merge Release to Development & Master Branches') {
                when {
                    expression {
                        currentBuild.currentResult == 'SUCCESS' && (BRANCH_MATCH ==~ /release.*/)
                    }
                }
                steps {
                    sshagent (credentials: ['jenkins-generated-ssh-key']) {
                        sh '''git config core.sshCommand "ssh -v -o StrictHostKeyChecking=no"
                              git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
                              git fetch --all
                              git commit -am "adding files generated during build" || true
                              git branch -a
                              git checkout master
                              git merge ${BRANCH_MATCH}
                              git commit -am "Merged ${BRANCH_MATCH} branch to master" || true
                              git push origin master
                              git checkout develop
                              git merge ${BRANCH_MATCH}
                              git commit -am "Merged ${BRANCH_MATCH} branch to develop" || true
                              git push origin develop
                           '''
                    }
                }
            }

            stage('Build and push master branch to PyPI') {
                when {
                    expression {
                        currentBuild.currentResult == 'SUCCESS' && (BRANCH_MATCH ==~ /master/)
                    }
                }
                steps {
                    sshagent (credentials: ['jenkins-generated-ssh-key']) {
                        sh '''source activate ${BUILD_TAG}-p3
                              python setup.py sdist
                              python setup.py bdist_wheel
                              twine upload --skip-existing dist/*
                           '''
                    }
                }
            }

        }
        post {
            always {
                sh 'conda remove --yes -n ${BUILD_TAG}-p3 --all'
                sh 'conda remove --yes -n ${BUILD_TAG}-p2 --all || true'
            }
            failure {
                slackSend(blocks: slackMessage("Failed"))
            }
            success {
                slackSend(blocks: slackMessage("Finished Successfully"))
            }
            unstable {
                slackSend(blocks: slackMessage("Unstable"))
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
String coverageReportUrl() {
    rn = repoName()
    bn = "${env.BRANCH_NAME}".replaceAll("/","%2F")
    return "${env.JENKINS_URL}/job/${rn}/job/${bn}/${env.BUILD_NUMBER}/cobertura/"
}
String branchName2() {
    rn = repoName()
    return "${env.BRANCH_NAME}"
}
def slackMessage(status) {

    badge = buildBadgeUrl()
    headline = "${env.REPO_NAME} build ${status}".toLowerCase()
    if(status == "Failed") {
        badgeImage = "https://raster.shields.io/badge/build-failed-red.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nSTATUS: *${status}*"
    } else if(status == "Unstable") {
        badgeImage = "https://raster.shields.io/static/v1?label=build&message=unstable&color=orange"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nSTATUS: *${status}*"
    } else if(status == "running") {
        badgeImage = "https://raster.shields.io/static/v1?label=build&message=running&color=purple"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nSTATUS: *${status}*"  
    } else {
        def crStr = readFile('reports/coverage.txt').trim()
        int cr = Math.floor(Double.valueOf(crStr)*100.0);
        badgeImage = "https://raster.shields.io/badge/build-passing-success.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nTEST COVERAGE: *${cr}%*\nSTATUS: *${status}*"
    }

    blocks = [
        [
          "type": "section",
          "text": [
            "type": "mrkdwn",
            "text": headline
          ],
        ],
        [
            "type": "image",
            "image_url": badgeImage,
            "alt_text": "status badge"
        ],
        [
          "type": "section",
          "text": [
            "type": "mrkdwn",
            "text": message
          ],
        ],
        [
          "type": "divider"
        ]
    ]
    return blocks
}
String buildBadgeUrl() {
    rn = repoName()
    bn = "${env.BRANCH_NAME}".replaceAll("/","%252F")    
    return "${env.JENKINS_URL}/buildStatus/icon?job=${rn}%2F${bn}"
}


