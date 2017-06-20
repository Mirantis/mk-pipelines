/**
* JS testing pipeline
* CREDENTIALS_ID - gerrit credentials id
* COMPOSE_PATH - path to compose file in repository
* NODE_IMAGE - NodeJS with NPM Docker image name
* COMMANDS - a list of command(s) to run
**/

gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()

def executeCmd(containerName, cmd) {
    stage(cmd) {
        assert containerName != null
        common.infoMsg("Starting command: ${cmd}")
        wrap([$class: 'AnsiColorBuildWrapper']) {
            sh("docker exec ${containerName} ${cmd}")
        }
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

node("vm") {
    def containerName
    def uniqId
    try {
        stage('Checkout source code') {
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
        stage('Generate config file for devops portal') {
            writeFile (
                file: "${workspace}/test_config.json",
                text: "${JSON_CONFIG}"
            )
       }
       stage('Start container') {
            def workspace = common.getWorkspace()
            def timeStamp = new Date().format("HHmmss", TimeZone.getTimeZone('UTC'))
            if (gerritRef) {
                uniqId = gerritRef.tokenize('/').takeRight(2).join('') + timeStamp
            } else {
                uniqId = defaultGitRef.tokenize('/').takeRight(2).join('') + timeStamp
            }
            sh("docker-compose -f ${COMPOSE_PATH} -p ${uniqId} up -d")
            containerName = "${uniqId}_devopsportal_1"
            common.successMsg("Container with id ${containerName} started.")
            sh("docker cp ${workspace}/. ${containerName}:/opt/workspace/")
        }
        executeCmd(containerName, "npm install")
        def cmds = COMMANDS.tokenize('\n')
        for (int i = 0; i < cmds.size(); i++) {
           timeout(5) {
               executeCmd(containerName, cmds[i])
           }
        }
    } catch (err) {
        currentBuild.result = 'FAILURE'
        common.errorMsg("Build failed due to error: ${err}")
        throw err
    } finally {
        common.sendNotification(currentBuild.result, "" ,["slack"])
        stage('Attach artifacts') {
            if (containerName != null) {
                sh("docker cp ${containerName}:/opt/workspace/test_output ${workspace}/test_output")
                archiveArtifacts(
                    artifacts: "${workspace}/test_output/screenshots/*.png",
                )
            }
        }
        stage('Cleanup') {
            if (containerName != null) {
                dockerCleanupCommands = ['stop', 'rm -f']
                for (int i = 0; i < dockerCleanupCommands.size(); i++) {
                    sh("docker-compose -f ${COMPOSE_PATH} -p ${uniqId} ${dockerCleanupCommands[i]} || true")
                }
                sh("docker network rm ${uniqId}_default || true")
                sh("rm -f ${workspace}/test_config.json || true")
                common.infoMsg("Container with id ${containerName} was removed.")
            }
        }
    }
}
