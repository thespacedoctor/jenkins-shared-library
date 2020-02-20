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
          
        }

        stages {
            stage('test') {
                when {
                    expression {
                        currentBuild.currentResult == 'SUCCESS'
                    }
                }
                steps {
                    script {
                        this = branchName()
                        sh '''echo ${BRANCH_NAME}
                              echo $this
                              echo ${REPO_NAME}
                              echo ${COVERAGE_URL}
                              aahhss
                           '''
                    }
                    
                }
            }

            stage ("Code pull"){
                steps{
                    checkout scm
                    script {
                        buildBadge.setStatus('running')
                    }
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
            stage('test') {
                when {
                    expression {
                        currentBuild.currentResult == 'SUCCESS'
                    }
                }
                steps {
                    script {
                        this = branchName()
                        sh '''echo ${BRANCH_NAME}
                              echo $this
                              echo ${env.BRANCH_NAME}
                              echo ${REPO_NAME}
                              echo ${COVERAGE_URL}
                              aahhss
                           '''
                    }
                    
                }
            }
            stage('Merge Hotfix/Feature to Development Branch') {
                when {
                    expression {
                        currentBuild.currentResult == 'SUCCESS' && (BRANCH_NAME ==~ /feature.*/)
                    }
                }
                steps {
                    sh '''git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
                          git fetch --all
                          git branch -a
                          git checkout develop
                          git merge ${env.BRANCH_NAME}
                          git commit -am "Merged ${env.BRANCH_NAME} branch to develop"
                          git push origin develop
                       '''
                }
            }

        }
        post {
            always {
                slackSend(blocks: slackMessage("Finished Successfully"))
                sh 'conda remove --yes -n ${BUILD_TAG}-p3 --all'
                sh 'conda remove --yes -n ${BUILD_TAG}-p2 --all'
            }
            failure {
                slackSend(blocks: slackMessage("Failed"))
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
def slackMessage(status) {

    badge = buildBadgeUrl()
    def cr = readFile('reports/coverage.txt').trim()

    if(status == "Failed") {
        badgeImage = "https://raster.shields.io/badge/build-failed-red.png"
    } else {
        badgeImage = "https://raster.shields.io/badge/build-passing-success.png"
    }

    blocks = [
        [
          "type": "section",
          "text": [
            "type": "mrkdwn",
            "text": "<${env.OVERVIEW_URL}|${env.REPO_NAME}> / <${env.BUILD_URL}|${env.BRANCH_NAME}>"
          ],
        ],
        [
            "type": "image",
            "image_url": badgeImage,
            "alt_text": "An incredibly cute kitten."
        ],
        [
          "type": "section",
          "text": [
            "type": "mrkdwn",
            "text": "\tBuild ${env.BUILD_NUMBER} ${status}\n\t<${env.COVERAGE_URL}|Coverage Rate = ${cr}>"
          ]
        ]
    ]
    return blocks
}
String buildBadgeUrl() {
    rn = repoName()
    bn = "${env.BRANCH_NAME}".replaceAll("/","%252F")    
    return "${env.JENKINS_URL}/buildStatus/icon?job=${rn}%2F${bn}"
}


