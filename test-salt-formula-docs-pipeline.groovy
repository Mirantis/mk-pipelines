/**
 * Pipeline for generating and testing sphinx generated documentation
 * MODEL_GIT_URL
 * MODEL_GIT_REF
 * CLUSTER_NAME
 *
 */

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
  gerritRef = null
}

common = new com.mirantis.mk.Common()
ssh = new com.mirantis.mk.Ssh()
gerrit = new com.mirantis.mk.Gerrit()
git = new com.mirantis.mk.Git()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()

timeout(time: 12, unit: 'HOURS') {
  node("python") {
    try {
       def workspace = common.getWorkspace()
       def masterName = "cfg01." + CLUSTER_NAME.replace("-","_") + ".lab"
       //def jenkinsUserIds = common.getJenkinsUserIds()
       def img = docker.image("tcpcloud/salt-models-testing:nightly")
       img.pull()
       img.inside("-u root:root --hostname ${masterName} --ulimit nofile=4096:8192 --cpus=2") {
           stage("Prepare salt env") {
              if(MODEL_GIT_REF != "" && MODEL_GIT_URL != "") {
                  checkouted = gerrit.gerritPatchsetCheckout(MODEL_GIT_URL, MODEL_GIT_REF, "HEAD", CREDENTIALS_ID)
              } else {
                throw new Exception("Cannot checkout gerrit patchset, MODEL_GIT_URL or MODEL_GIT_REF is null")
              }
              if(checkouted) {
                if (fileExists('classes/system')) {
                    ssh.prepareSshAgentKey(CREDENTIALS_ID)
                    dir('classes/system') {
                      // XXX: JENKINS-33510 dir step not work properly inside containers
                      //remoteUrl = git.getGitRemote()
                      ssh.ensureKnownHosts("https://github.com/Mirantis/reclass-system-salt-model")
                    }
                    ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
                }
              }
              // install all formulas
              sh("apt-get update && apt-get install -y salt-formula-*")
              withEnv(["MASTER_HOSTNAME=${masterName}", "CLUSTER_NAME=${CLUSTER_NAME}", "MINION_ID=${masterName}"]){
                    sh("cp -r ${workspace}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts")
                    sh("""bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts \
                          && source_local_envs \
                          && configure_salt_master \
                          && configure_salt_minion \
                          && install_salt_formula_pkg; \
                          saltservice_restart; \
                          saltmaster_init'""")
              }
           }
           stage("Checkout formula review"){
              if(gerritRef){
                //TODO: checkout gerrit review and replace formula content in directory
                // gerrit.gerritPatchsetCheckout([credentialsId: CREDENTIALS_ID])
              }else{
                common.successMsg("Test triggered manually, so skipping checkout formula review stage")
              }
           }
           stage("Generate documentation"){
                def saltResult = sh(script:"salt-call state.sls salt.minion,sphinx.server,nginx", returnStatus:true)
                if(saltResult > 0){
                    common.warnMsg("Salt call salt.minion,sphinx.server,nginx failed but continuing")
                }
           }
           stage("Publish outputs"){
                try{
                  sh("mkdir ${workspace}/output")
                  //TODO: verify existance of created output files
                  // /srv/static/sites/reclass_doc will be used for publishHTML step
                  sh("tar -zcf ${workspace}/output/docs-html.tar.gz /srv/static/sites/reclass_doc")
                  sh("cp -R /srv/static/sites/reclass_doc ${workspace}")
                  publishHTML (target: [
                      reportDir: 'reclass_doc',
                      reportFiles: 'index.html',
                      reportName: "Reclass-documentation"
                  ])
                  // /srv/static/extern will be used as tar artifact
                  sh("tar -zcf ${workspace}/output/docs-src.tar.gz /srv/static/extern")
                  archiveArtifacts artifacts: "output/*"
                }catch(Exception e){
                    common.errorMsg("Documentation publish stage failed!")
                }finally{
                   sh("rm -r ./output")
                }
           }
       }
    } catch (Throwable e) {
      // If there was an error or exception thrown, the build failed
      currentBuild.result = "FAILURE"
      currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
      throw e
    } finally {
      common.sendNotification(currentBuild.result, "", ["slack"])
    }
  }
}
