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

        triggers {
            cron((env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'master') ? 'H H * * H' : '')
        }

        options {
            skipDefaultCheckout()
            // KEEP THE 10 MOST RECENT BUILDS
            buildDiscarder(logRotator(numToKeepStr: '2'))
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
          RTD_URL=rtdUrl()
          PYPI_URL=pypiUrl()
          GITHUB_URL=githubUrl()
          PULLREQUEST_DEV=githubPullRequestToDev()
          PULLREQUEST_MAIN=githubPullRequestToMain()
        }




        stages {

            stage ("Check Branch Types") {
                when {
                    expression {
                        (BRANCH_MATCH ==~ /feature.*/ || BRANCH_MATCH ==~ /hotfix.*/)
                    }
                }
                steps {
                    script {
                        slackSend(message: "${env.REPO_NAME} - ${env.BRANCH_MATCH}".toLowerCase(), blocks: slackMessage('pull-request')) 
                        currentBuild.result = 'ABORTED'
                        error("Aborting the build - submit a pull request to test")  
                    }
                }
            }


            stage ("Code pull") {
                steps{
                    cleanWs()
                    script {
                        slackSend(message: "${env.REPO_NAME} - ${env.BRANCH_MATCH} build running".toLowerCase(), blocks: slackMessage('running'))
                        buildBadge.setStatus('running')
                        def scmVars = checkout scm 

                        env.GIT_COMMIT = scmVars.GIT_COMMIT
                        println scmVars

                        // Determine actual PR commit, if necessary
                        sh 'mkdir -p tmp'
                        sh 'git rev-parse HEAD | git log --pretty=%P -n 1 --date-order > tmp/MERGE_COMMIT_PARENTS'
                        sh 'cat tmp/MERGE_COMMIT_PARENTS'
                        merge_commit_parents = readFile('tmp/MERGE_COMMIT_PARENTS').trim()
                        if (merge_commit_parents.length() > 40) {
                            echo 'More than one merge commit parent signifies that the merge commit is not the PR commit'
                            echo "Changing git_commit from '${env.GIT_COMMIT}' to '${merge_commit_parents.take(40)}'"
                            env.GIT_COMMIT = merge_commit_parents.take(40)
                        } else {
                            echo 'Only one merge commit parent signifies that the merge commit is also the PR commit'
                            echo "Keeping git_commit as '${env.GIT_COMMIT}'"
                        }
                    }
                    
                }
            }

            stage('Clone/Update Settings Files') {
                steps {

                    sshagent (credentials: ['jenkins-generated-ssh-key']) {
                        sh '''
                              mkdir -p ~/git_repos/_misc_
                              cd ~/git_repos/_misc_
                              git clone git@github.com:thespacedoctor/settings.git || true
                              cd settings
                              git config core.sshCommand "ssh -v -o StrictHostKeyChecking=no"
                              git fetch --all
                              git add . --all
                              git commit -am "adding new files from jenkins machine" || true
                              git pull
                              git push origin master
                           '''
                    }
                }

            }

            stage('Build conda python 2.7 environment & install code') {
                
                when {
                    expression {
                        PYTHON2 == '' || PYTHON2 == true || PYTHON2 == 'true'
                    }
                }
                steps {
                    updateGithubCommitStatus(currentBuild,  "jenkins/thespacedoctor", BUILD_URL, "In Progress", "PENDING")
                    sh '''conda create --yes -n ${BUILD_TAG}-p2 python=2.7 pip 
                                  source activate ${BUILD_TAG}-p2
                                  conda install pytest pandas coverage pytest-cov ${EXTRA_CONDA_PACKAGES}
                                  ${EXTRA_CONDA_INSTALL_COMMANDS}
                                  pip install coverage-badge ${EXTRA_PIP_PACKAGES}
                                  pip install -e .
                                '''
                }
            }

            stage('Build conda python 3.7 environment & install code') {
                steps {
                    updateGithubCommitStatus(currentBuild,  "jenkins/thespacedoctor", BUILD_URL, "In Progress", "PENDING")
                    sh '''if [ -f "environment.ymll" ]; then
                            conda env create -n ${BUILD_TAG}-p3 --file=environment.yml
                          else 
                            conda create --yes -n ${BUILD_TAG}-p3 python=3.7 pip twine sphinx
                          fi
                          source activate ${BUILD_TAG}-p3 
                          conda install pytest coverage pytest-cov sphinx pip twine ${EXTRA_CONDA_PACKAGES} 
                          conda install -c conda-forge sphinxcontrib-apidoc
                          ${EXTRA_CONDA_INSTALL_COMMANDS}
                          pip install coverage-badge ${EXTRA_PIP_PACKAGES} 
                          pip install -e .
                        '''
                    // echo sh(script: 'ls -al', returnStdout: true).trim()
                    // echo sh(script: 'source activate ${BUILD_TAG}-p3 ; which sphinx-apidoc', returnStdout: true).trim()
                }

            }
        
            stage('Build Docs in Python 3') {
                when {
                    expression {
                        TESTDOCS == '' || TESTDOCS == true || TESTDOCS == 'true'
                    }
                }
                steps {
                    echo sh(script: 'source activate ${BUILD_TAG}-p3 ; which sphinx-apidoc', returnStdout: true).trim()
                    sh  ''' source activate ${BUILD_TAG}-p3
                            cd docs
                            pip install -r requirements.txt --use-deprecated=legacy-resolver
                            source activate ${BUILD_TAG}-p3
                            SPHINX_APIDOC_OPTIONS='members,undoc-members,show-inheritance,inherited-members,member-order' sphinx-apidoc -fMeTP  -o source/_api ../ ../setup.py ../${REPO_NAME}/__version__.py ../*/tests* ../*/*/tests* ../*/*/*/tests* ../*/*/*/*/tests*
                            make html SPHINXOPTS=-vP
                        '''
                }
            }

            stage('Unit tests for Python 2') {
                when {
                    expression {
                        PYTHON2 == '' || PYTHON2 == true || PYTHON2 == 'true'
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
                        // Archive unit tests for the future.
                        junit allowEmptyResults: true, testResults: 'test-reports/unit_tests_p2.xml'
                    }
                }
            }

            stage('Unit tests for Python 3') {
                steps {
                    script {
                        try {
                            sh  ''' source activate ${BUILD_TAG}-p3
                                    pytest --verbose --junit-xml test-reports/unit_tests_p3.xml --cov --cov-report xml:reports/coverage.xml --cov-report html
                                    coverage-badge -f -o coverage.svg
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

            // stage('Merge Hotfix/Feature to Development Branch') {
            //     when {
            //         expression {
            //             currentBuild.currentResult == 'SUCCESS' && (BRANCH_MATCH ==~ /feature.*/ || BRANCH_MATCH ==~ /hotfix.*/)
            //         }
            //     }
            //     steps {
            //         sshagent (credentials: ['jenkins-generated-ssh-key']) {
            //             sh '''git config core.sshCommand "ssh -v -o StrictHostKeyChecking=no"
            //                   git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
            //                   git fetch --all
            //                   git add . --all
            //                   git commit -am "adding files generated during build" || true
            //                   git branch -a
            //                   git checkout ${BRANCH_MATCH}
            //                   git checkout develop
            //                   git merge -Xours ${BRANCH_MATCH}
            //                   git add . --all
            //                   git commit -am "Merged ${BRANCH_MATCH} branch to develop"  || true
            //                   git push origin develop
            //                '''
            //         }
            //     }
            // }


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
                              git add . --all
                              git commit -am "adding files generated during build" || true
                              git branch -a
                              git checkout ${BRANCH_MATCH}
                              git checkout master
                              git merge -Xtheirs ${BRANCH_MATCH}
                              git add . --all
                              git commit -am "Merged ${BRANCH_MATCH} branch to master" || true
                              git push origin master
                              sleep 60
                              git checkout develop
                              git merge -Xtheirs ${BRANCH_MATCH}
                              git add . --all
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
                              twine upload --skip-existing dist/*  || true
                              python setup.py bdist_wheel
                              twine upload --skip-existing dist/*  || true
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
                slackSend(message: "${env.REPO_NAME} - ${env.BRANCH_MATCH} build failed".toLowerCase(), blocks: slackMessage("Failed"))
                updateGithubCommitStatus(currentBuild,  "jenkins/thespacedoctor", BUILD_URL, "Build Failed.", 'FAILURE')
            }
            success {
                slackSend(message: "${env.REPO_NAME} - ${env.BRANCH_MATCH} build successful".toLowerCase(), blocks: slackMessage("Finished Successfully"))
                updateGithubCommitStatus(currentBuild,  "jenkins/thespacedoctor", BUILD_URL, "Build Success!", 'SUCCESS')
            }
            unstable {
                slackSend(message: "${env.REPO_NAME} - ${env.BRANCH_MATCH} build unstable".toLowerCase(), blocks: slackMessage("Unstable"))
                updateGithubCommitStatus(currentBuild,  "jenkins/thespacedoctor", BUILD_URL, "Build Unstable.", 'UNSTABLE')
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
String rtdUrl() {
    rn = repoName()
    return "https://${rn}.readthedocs.io/en/master/"
}

String githubUrl() {
    rn = repoName()
    return "https://github.com/thespacedoctor/${rn}/tree/${env.BRANCH_NAME}".replaceAll("/tree/PR-","/pull/")
}

String githubPullRequestToDev() {
    rn = repoName()
    return "https://github.com/thespacedoctor/${rn}/compare/develop...${env.BRANCH_NAME}?expand=1"
}

String githubPullRequestToMain() {
    rn = repoName()
    return "https://github.com/thespacedoctor/${rn}/compare/master...${env.BRANCH_NAME}?expand=1"
}

String pypiUrl() {
    rn = repoName()
    return "https://pypi.org/project/${rn}"
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
    if(status == "Failed") {
        badgeImage = "https://cdn.jsdelivr.net/gh/thespacedoctor/jenkins-shared-library/resources/build-failed-red.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nSTATUS: *${status}*\nGITHUB: *<${env.GITHUB_URL}|${env.REPO_NAME}/${env.BRANCH_MATCH}>*"
    } else if(status == "Unstable") {
        badgeImage = "https://cdn.jsdelivr.net/gh/thespacedoctor/jenkins-shared-library/resources/build-unstable-orange.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nSTATUS: *${status}*\nGITHUB: *<${env.GITHUB_URL}|${env.REPO_NAME}/${env.BRANCH_MATCH}>*"
    } else if(status == "running") {
        badgeImage = "https://cdn.jsdelivr.net/gh/thespacedoctor/jenkins-shared-library/resources/build-running-blueviolet.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nSTATUS: *${status}*\nGITHUB: *<${env.GITHUB_URL}|${env.REPO_NAME}/${env.BRANCH_MATCH}>*"  
    } else if(status == "pull-request") {
        badgeImage = "https://cdn.jsdelivr.net/gh/thespacedoctor/jenkins-shared-library/resources/pull-request.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nGITHUB: *<${env.GITHUB_URL}|${env.REPO_NAME}/${env.BRANCH_MATCH}>*\nPull Request - DEV: *<${env.PULLREQUEST_DEV}|${env.REPO_NAME}>*\nPull Request - MAIN: *<${env.PULLREQUEST_MAIN}|${env.REPO_NAME}>*"
    } else if(BRANCH_MATCH ==~ /PR\-.*/) {
        def crStr = readFile('reports/coverage.txt').trim()
        int cr = Math.floor(Double.valueOf(crStr)*100.0);
        badgeImage = "https://cdn.jsdelivr.net/gh/thespacedoctor/jenkins-shared-library/resources/build-passing-success.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nTEST COVERAGE: *${cr}%*\nSTATUS: *${status}*\nREADTHEDOCS: *<${env.RTD_URL}|${env.REPO_NAME}>*\nPYPI: *<${env.PYPI_URL}|${env.REPO_NAME}>*\nGITHUB: *<${env.GITHUB_URL}|${env.REPO_NAME}/${env.BRANCH_MATCH}>*\n*<${env.GITHUB_URL}|MERGE PULL-REQUEST HERE>*"
    } else {
        def crStr = readFile('reports/coverage.txt').trim()
        int cr = Math.floor(Double.valueOf(crStr)*100.0);
        badgeImage = "https://cdn.jsdelivr.net/gh/thespacedoctor/jenkins-shared-library/resources/build-passing-success.png"
        message = "REPO: *<${env.OVERVIEW_URL}|${env.REPO_NAME}>*\nBRANCH: *<${env.BUILD_URL}|${env.BRANCH_MATCH}>*\nBUILD: *#${env.BUILD_NUMBER}*\nTEST COVERAGE: *${cr}%*\nSTATUS: *${status}*\nREADTHEDOCS: *<${env.RTD_URL}|${env.REPO_NAME}>*\nPYPI: *<${env.PYPI_URL}|${env.REPO_NAME}>*\nGITHUB: *<${env.GITHUB_URL}|${env.REPO_NAME}/${env.BRANCH_MATCH}>*"
    }

    blocks = [
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
    bn = "${env.BRANCH_NAME}".replaceAll("/","%2F")    
    return "${env.JENKINS_URL}/buildStatus/icon?job=${rn}%2F${bn}"
}

def getCommitSha() {
  sh "git rev-parse HEAD > /tmp/current-commit"
  return readFile("/tmp/current-commit").trim()
}


def updateGithubCommitStatus(build, String context, String buildUrl, String message, String state) {
  // workaround https://issues.jenkins-ci.org/browse/JENKINS-38674
  // commitSha = getCommitSha()
  println "Updating Github Commit Status"
  println "repoUrl ${env.BRANCH_NAME}"
  println "commitSha ${env.GIT_COMMIT}"
  println "build result: ${build.result}, currentResult: ${build.currentResult}"

  step([
    $class: 'GitHubCommitStatusSetter',
    reposSource: [$class: "ManuallyEnteredRepositorySource", url: env.BRANCH_NAME],
    commitShaSource: [$class: "ManuallyEnteredShaSource", sha: env.GIT_COMMIT],
    errorHandlers: [[$class: 'ShallowAnyErrorHandler']],
    contextSource: [$class: "ManuallyEnteredCommitContextSource", context: context],
    statusBackrefSource: [$class: "ManuallyEnteredBackrefSource", backref: buildUrl],

    statusResultSource: [
      $class: 'ConditionalStatusResultSource',
      results: [
        [$class: 'AnyBuildResult', state: state, message: message]
      ]
    ]
  ])
}



