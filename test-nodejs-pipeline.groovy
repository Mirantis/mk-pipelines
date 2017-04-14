/**
* JS testing pipeline
* CREDENTIALS_ID - gerrit credentials id
* NODE_IMAGE - NodeJS with NPM Docker image name
* COMMANDS - a list of command(s) to run
**/

gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()

def executeCmd(containerId, cmd) {
    stage(cmd) {
        assert containerId != null
        common.infoMsg("Starting command: ${cmd}")
        def output = sh(
            script: "docker exec ${containerId} ${cmd}",
            returnStdout: true,
        )
        common.infoMsg(output)
        common.successMsg("Successfully completed: ${cmd}")
    }
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

node("docker") {
    def containerId
    try {
        stage('Checkout source code') {
            if (gerritRef) {
                // job is triggered by Gerrit
                gerrit.gerritPatchsetCheckout ([
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
        stage('Start container') {
            def workspace = common.getWorkspace()
            containerId = sh(
                script: "docker run -d ${NODE_IMAGE}",
                returnStdout: true,
            ).trim()
            common.successMsg("Container with id ${containerId} started.")
            sh("docker cp ${workspace}/. ${containerId}:/opt/workspace/")
        }
        executeCmd(containerId, "npm install")
        def cmds = COMMANDS.tokenize('\n')
        for (int i = 0; i < cmds.size(); i++) {
           executeCmd(containerId, cmds[i])
        }
    } catch (err) {
        currentBuild.result = 'FAILURE'
        common.errorMsg("Build failed due to error: ${err}")
        throw err
    } finally {
        common.sendNotification(currentBuild.result, "" ,["slack"])
        stage('Cleanup') {
            if (containerId != null) {
                sh("docker stop -t 0 ${containerId}")
                sh("docker rm ${containerId}")
                common.infoMsg("Container with id ${containerId} was removed.")
            }
        }
    }
}
