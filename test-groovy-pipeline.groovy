/**
* Groovy code testing pipeline
* CREDENTIALS_ID - gerrit credentials id
* GRADLE_IMAGE - gradle image name
* GRADLE_CMD - command(s) for gradle
*
**/

gerrit = new com.mirantis.mk.Gerrit()
common = new com.mirantis.mk.Common()

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

node("docker"){
    try {
        stage ('Checkout source code'){
          if (gerritRef) {
            // job is triggered by Gerrit
            checkouted = gerrit.gerritPatchsetCheckout ([
              credentialsId : CREDENTIALS_ID
            ])
          } else if(defaultGitRef && defaultGitUrl) {
              checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
          }
          if(!checkouted){
            common.errorMsg("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
          }
        }
        stage ('Run Codenarc tests'){
            if(checkouted){
              def workspace = common.getWorkspace()
              def jenkinsUID = common.getJenkinsUid()
              def jenkinsGID = common.getJenkinsGid()
              def gradle_report = sh (script: "docker run --rm -v ${workspace}:/usr/bin/app:rw -u ${jenkinsUID}:${jenkinsGID} ${GRADLE_IMAGE} ${GRADLE_CMD}", returnStdout: true).trim()
              // Compilation failure doesn't fail the build
              // Check gradle output explicitly
              common.infoMsg(gradle_report)
              if ( gradle_report =~ /Compilation failed/ ) {
                  throw new Exception("COMPILATION FAILED!")
              }
            }
        }

    } catch (Throwable e) {
        currentBuild.result = 'FAILURE'
        try{
            def errLog = readFile('build/reports/codenarc/main.txt')
            if(errLog){
                common.errorMsg("Error log: ${errLog}")
            }
        }catch(ex){}
        throw e
    } finally {
        // send notification
        common.sendNotification(currentBuild.result, "" ,["slack"])
    }
}
