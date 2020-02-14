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
        }

        stages {
            stage ("Code pull"){
                steps{
                    checkout scm
                }
            }

            stage('Build conda python 3.7 environment') {
                steps {
                    sh '''conda create --yes -n ${BUILD_TAG}-p3 python=3.7 pip
                          source activate ${BUILD_TAG}-p3 
                          python setup.py install
                        '''
                }
            }
            stage('Test conda python 3.7 environment') {
                steps {
                    sh '''source activate ${BUILD_TAG}-p3 
                          pip list
                          which pip
                          which python
                        '''
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
String slackMessage(status) {
    return "${env.BRANCH_NAME}\n ${env.REPO_NAME}\n ${env.NODE_NAME} Build ${env.BUILD_NUMBER} ${status} (<${env.JENKINS_URL}/blue/organizations/jenkins/${env.JOB_NAME}/${env.BUILD_NUMBER}/pipeline|Open>)"
}
