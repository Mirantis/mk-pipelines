/**
* Groovy code testing pipeline
* CREDENTIALS_ID - gerrit credentials id
* GRADLE_IMAGE - gradle image name
* GRADLE_CMD - command(s) for gradle
*
**/

gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()

node("docker"){
    try {
        stage ('Checkout source code'){
            gerrit.gerritPatchsetCheckout ([
              credentialsId : CREDENTIALS_ID,
              withWipeOut : true
            ])
        }
        stage ('Run Codenarc tests'){
            def workspace = common.getWorkspace()
            def gradle_report = sh (script: "docker run --rm -v ${workspace}:/usr/bin/app:rw ${GRADLE_IMAGE} ${GRADLE_CMD}",
                                    returnStdout: true).trim()
            // Compilation failure doesn't fail the build
            // Check gradle output explicitly
            common.infoMsg(gradle_report)
            if ( gradle_report =~ /Compilation failed/ ) {
                throw new Exception("COMPILATION FAILED!")
            }
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        def errLog = readFile('build/reports/codenarc/main.txt')
        if(errLog){
            print "Error log: ${errLog}"
        }
        throw e
    } finally {
        // send notification
        common.sendNotification(currentBuild.result, "" ,["slack"])
    }
}
