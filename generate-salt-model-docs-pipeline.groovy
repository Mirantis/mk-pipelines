/**
 * Pipeline for generating sphinx reclass generated documentation
 * MODEL_GIT_URL
 * MODEL_GIT_REF
 * CLUSTER_NAME
 *
 */

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
       def jenkinsUserIds = common.getJenkinsUserIds()
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
                      // XXX: JENKINS-33510 dir step not work properly inside containers, so let's taky reclass system model directly
                      //remoteUrl = git.getGitRemote()
                      ssh.ensureKnownHosts("https://github.com/Mirantis/reclass-system-salt-model")
                    }
                    ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
                }
              }
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
           stage("Generate documentation"){
                def saltResult = sh(script:"salt-call state.sls salt.minion,sphinx.server,nginx", returnStatus:true)
                if(saltResult > 0){
                    common.warnMsg("Salt call salt.minion,sphinx.server,nginx failed but continuing")
                }
           }
           stage("Publish outputs"){
                try {
                    // /srv/static/sites/reclass_doc will be used for publishHTML step
                    // /srv/static/extern will be used as tar artifact
                    def outputPresent = sh(script:"ls /srv/static/sites/reclass_doc > /dev/null 2>&1 && ls /srv/static/extern  > /dev/null 2>&1", returnStatus: true) == 0
                    if(outputPresent){
                      sh("""mkdir ${workspace}/output && \
                            tar -zcf ${workspace}/output/docs-html.tar.gz /srv/static/sites/reclass_doc && \
                            tar -zcf ${workspace}/output/docs-src.tar.gz /srv/static/extern && \
                            cp -R /srv/static/sites/reclass_doc ${workspace}/output && \
                            chown -R ${jenkinsUserIds[0]}:${jenkinsUserIds[1]} ${workspace}/output""")

                      publishHTML (target: [
                          alwaysLinkToLastBuild: true,
                          keepAll: true,
                          reportDir: 'output/reclass_doc',
                          reportFiles: 'index.html',
                          reportName: "Reclass-documentation"
                      ])
                      archiveArtifacts artifacts: "output/*"
                  } else {
                    common.errorMsg("Documentation publish failed, one of output directories /srv/static/sites/reclass_doc or /srv/static/extern not exists!")
                  }
                } catch(Exception e) {
                    common.errorMsg("Documentation publish stage failed!")
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
