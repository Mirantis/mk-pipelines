/**
* JS testing pipeline
* CREDENTIALS_ID - gerrit credentials id
* NODE_IMAGE - NodeJS with NPM Docker image name
* COMMANDS - a list of command(s) to run
**/

gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()

node("docker") {
    def containerID
    try {
        stage ('Checkout source code') {
            gerrit.gerritPatchsetCheckout ([
              credentialsId : CREDENTIALS_ID,
              withWipeOut : true,
            ])
        }
        stage ('Start container') {
           def workspace = common.getWorkspace()
           containerID = sh(
               script: "docker run -d -v ${workspace}:/opt/workspace:rw ${NODE_IMAGE}",
               returnStdout: true,
           ).trim()
        }
        stage ('Execute commands') {
            assert containerID != null
            def cmds = COMMANDS.tokenize('\n')
            for (int i = 0; i < cmds.size(); i++) {
               def cmd = cmds[i]
               def output = sh(
                   script: "docker exec ${containerID} ${cmd}",
                   returnStdout: true,
               ).trim()
               common.infoMsg(output)
            }
        }
    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        common.errorMsg("Build failed due to some commands failed.")
        throw e
    } finally {
        common.sendNotification(currentBuild.result, "" ,["slack"])
        stage ('Remove container') {
            if (containerID != null) {
                sh "docker stop -t 0 ${containerID}"
                sh "docker rm ${containerID}"
            }
        }
    }
}
