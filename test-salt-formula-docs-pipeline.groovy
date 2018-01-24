/**
 * Pipeline for generating and testing sphinx generated documentation
 *
 * Parameters:
 *   SALT_MASTER_URL
 *   SALT_MASTER_CREDENTIALS
 *
 */

def gerritRef
try {
  gerritRef = GERRIT_REFSPEC
} catch (MissingPropertyException e) {
  gerritRef = null
}

common = new com.mirantis.mk.Common()
gerrit = new com.mirantis.mk.Gerrit()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()

timeout(time: 12, unit: 'HOURS') {
  node("python") {
    try {
       def masterName = "cfg01.test-salt-formulas-docs.lab"
       def img = docker.image("tcpcloud/salt-models-testing:latest")
       img.pull()
       img.inside("-u root:root --hostname ${masterName}--ulimit nofile=4096:8192 --cpus=2") {
           stage("Prepare salt env") {
              withEnv(["MASTER_HOSTNAME=${masterName}", "CLUSTER_NAME=test-salt-formulas-docs-cluster", "MINION_ID=${masterName}"]){
                    //TODO: we need to have some simple model or maybe not, bootstrap.sh script generates test model
                    //sh("cp -r ${testDir}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts")
                    sh("echo '127.0.1.2  salt' >> /etc/hosts")
                    // sedding apt to internal -  should be not necessary
                    sh("cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt-mk.mirantis.com/apt.mirantis.net:8085/g' {} \\;")
                    sh("cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt.mirantis.com/apt.mirantis.net:8085/g' {} \\;")
                    sh("""bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts \
                          && source_local_envs \
                          && configure_salt_master \
                          && configure_salt_minion \
                          && install_salt_formula_pkg; \
                          saltservice_restart; \
                          saltmaster_init'""")
              }
           }
           stage("Install all formulas"){
              sh("apt update && apt install -y salt-formula-*")
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
               def pepperEnv = common.getWorkspace() + "/venvPepper"
               python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
               salt.enforceState(venvPepper, masterName , 'sphinx' , true)
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
