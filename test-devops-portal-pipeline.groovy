/**
* OSS - The DevOps Portal Testing Pipeline
* CREDENTIALS_ID - gerrit credentials id
**/

gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()

def getProjectName(gerritRef, defaultGitRef) {
    def refSpec
    if (gerritRef) {
        refSpec = gerritRef
    } else {
        refSpec = defaultGitRef
    }
    def refValue = refSpec.tokenize('/').takeRight(2).join('')
    return "oss${BUILD_NUMBER}${refValue}"
}

def executeCmd(user, project, cmd) {
    common.infoMsg("Starting command: ${cmd}")
    wrap([$class: 'AnsiColorBuildWrapper']) {
        // Docker sets HOME=/ ignoring that it have to be HOME=/opt/workspace,
        // as `docker-compose exec` does not support to pass environment
        // variables, then `docker exec` is used.
        sh("docker exec --user=${user} --env=HOME=/opt/workspace ${project}_devopsportal_1 ${cmd}")
    }
    common.successMsg("Successfully completed: ${cmd}")
}

def gerritRef
try {
    gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
    gerritRef = null
}

def defaultGitRef, defaultGitUrl
try {
    defaultGitRef = DEFAULT_GIT_REF
    defaultGitUrl = DEFAULT_GIT_URL
} catch (MissingPropertyException e) {
    defaultGitRef = null
    defaultGitUrl = null
}
def checkouted = false

node("vm") {
    def composePath = 'docker/stack/docker-compose.yml'
    def projectName
    def jenkinsUser

    try {
        stage('Checkout Source Code') {
            if (gerritRef) {
                // job is triggered by Gerrit
                checkouted = gerrit.gerritPatchsetCheckout ([
                    credentialsId : CREDENTIALS_ID,
                    withWipeOut : true,
                ])
             } else if(defaultGitRef && defaultGitUrl) {
                 checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
             }
             if(!checkouted){
                 throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
             }
        }

        projectName = getProjectName(gerritRef, defaultGitRef)

        stage('Setup Up Stack') {
            sh("docker-compose --file ${composePath} --project-name=${projectName} pull")
            sh("docker-compose --file ${composePath} --project-name=${projectName} up -d --force-recreate")
            common.successMsg("Stack with the ${projectName} is started.")
        }

        def jenkinsUID = common.getJenkinsUid()
        def jenkinsGID = common.getJenkinsGid()

        jenkinsUser = "${jenkinsUID}:${jenkinsGID}"

        stage('Print Environment Information') {
            sh("docker-compose version")
            sh("docker version")
            executeCmd(jenkinsUser, projectName, "npm config get")
            executeCmd(jenkinsUser, projectName, "env")
            executeCmd(jenkinsUser, projectName, "ls -lan")
        }

        stage('Install Dependencies') {
            executeCmd(jenkinsUser, projectName, "npm install")
        }
        stage('Run Linter Tests') {
            executeCmd(jenkinsUser, projectName, "npm run lint")
        }
        stage('Run Unit Tests') {
            timeout(4) {
                executeCmd(jenkinsUser, projectName, "npm run test:unit")
            }
        }
        stage('Run Function Tests') {
            timeout(20) {
                try {
                    executeCmd(jenkinsUser, projectName, "npm run test:functional")
                } catch (err) {
                    archiveArtifacts(
                        artifacts: "test_output/**/*.png",
                        allowEmptyArchive: true,
                    )
                    throw err
                }
            }
        }
    } catch (err) {
        currentBuild.result = 'FAILURE'
        common.errorMsg("Build failed due to error: ${err}")
        throw err
    } finally {
        common.sendNotification(currentBuild.result, "" ,["slack"])
        stage('Cleanup') {
            wrap([$class: 'AnsiColorBuildWrapper']) {
                sh("docker-compose -f ${composePath} -p ${projectName} down")
            }
        }
    }
}

