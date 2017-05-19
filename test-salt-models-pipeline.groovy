
/**
 *  Test salt models pipeline
 *  DEFAULT_GIT_REF
 *  DEFAULT_GIT_URL
 *  CREDENTIALS_ID
 */

def common = new com.mirantis.mk.Common()
def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
def git = new com.mirantis.mk.Git()

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
def merged = false
node("python&&docker") {
  try{
    stage("checkout") {
      if (gerritRef) {
        // job is triggered by Gerrit
        // test if change aren't already merged
        def gerritChange = gerrit.getGerritChange(GERRIT_NAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, CREDENTIALS_ID)
        merged = gerritChange.status == "MERGED"
        if(!merged){
          checkouted = gerrit.gerritPatchsetCheckout ([
            credentialsId : CREDENTIALS_ID
          ])
        } else{
          common.successMsg("Change ${GERRIT_CHANGE_NUMBER} is already merged, no need to gate them")
        }
      } else if(defaultGitRef && defaultGitUrl) {
          checkouted = gerrit.gerritPatchsetCheckout(defaultGitUrl, defaultGitRef, "HEAD", CREDENTIALS_ID)
      }
      if(checkouted){
        if (fileExists('classes/system')) {
          ssh.prepareSshAgentKey(CREDENTIALS_ID)
          dir('classes/system') {
            remoteUrl = git.getGitRemote()
            ssh.ensureKnownHosts(remoteUrl)
          }
          ssh.agentSh("git submodule init; git submodule sync; git submodule update --recursive")
        }
      }else if(!merged){
        throw new Exception("Cannot checkout gerrit patchset, GERRIT_REFSPEC and DEFAULT_GIT_REF is null")
      }
    }

    stage("test-nodes") {
      def nodes = sh(script: "find ./nodes -type f -name 'cfg*.yml'", returnStdout: true).tokenize()
      def buildSteps = [:]
      if(nodes.size() > 1){
          if(nodes.size() <= 3 && PARALLEL_NODE_GROUP_SIZE.toInteger() != 1) {
            common.infoMsg("Found <=3  cfg nodes, running parallel test")
             for(int i=0; i < nodes.size();i++){
               def basename = sh(script: "basename ${partition[k]} .yml", returnStdout: true).trim()
               buildSteps.put("node-${basename}", { setupAndTestNode(basename) })
             }
             parallel buildSteps
          }else{
            common.infoMsg("Found more than 3 cfg nodes or debug enabled, running parallel group test with ${PARALLEL_NODE_GROUP_SIZE} nodes")
            def partitions = common.partitionList(nodes, PARALLEL_NODE_GROUP_SIZE.toInteger())
            for (int i=0; i < partitions.size();i++) {
              def partition = partitions[i]
              buildSteps.put("partition-${i}", new HashMap<String,org.jenkinsci.plugins.workflow.cps.CpsClosure2>())
              for(int k=0; k < partition.size;k++){
                  def basename = sh(script: "basename ${partition[k]} .yml", returnStdout: true).trim()
                  buildSteps.get("partition-${i}").put(basename, { setupAndTestNode(basename) })
              }
            }
            common.serial(buildSteps)
          }
      }else{
          common.infoMsg("Found one cfg node, running single test")
          def basename = sh(script: "basename ${nodes[0]} .yml", returnStdout: true).trim()
          setupAndTestNode(basename)
      }
    }

  } catch (Throwable e) {
     // If there was an error or exception thrown, the build failed
     currentBuild.result = "FAILURE"
     throw e
  } finally {
     common.sendNotification(currentBuild.result,"",["slack"])
  }
}

def setupAndTestNode(masterName) {
  def img = docker.image("ubuntu:latest")
  def saltOpts = "--retcode-passthrough --force-color"
  def common = new com.mirantis.mk.Common()
  def workspace = common.getWorkspace()

  img.inside("-u root:root --hostname=${masterName}") {
    wrap([$class: 'AnsiColorBuildWrapper']) {
      sh("mkdir -p /srv/salt/ || true")
      sh("cp -r ${workspace} /srv/salt/reclass")
      sh("apt-get update && apt-get install -y curl subversion git python-pip sudo python-pip python-dev zlib1g-dev git")
      sh("svn export --force https://github.com/salt-formulas/salt-formulas/trunk/deploy/scripts /srv/salt/scripts")
      sh("git config --global user.email || git config --global user.email 'ci@ci.local'")
      sh("git config --global user.name || git config --global user.name 'CI'")
      sh("pip install git+https://github.com/epcim/reclass.git@pr/fix/fix_raise_UndefinedVariableError")
      sh("ls -lRa /srv/salt/reclass")

      // setup iniot and verify salt master and minions
      withEnv(["FORMULAS_SOURCE=pkg", "DEBUG=1", "MASTER_HOSTNAME=${masterName}", "MINION_ID=${masterName}", "HOSTNAME=cfg01", "DOMAIN=mk-ci.local"]){
          sh("bash -c 'echo $MASTER_HOSTNAME'")
          sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && system_config'")
          sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && saltmaster_bootstrap'")
          sh("bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && saltmaster_init'")
      }
      sh("ls -lRa /srv/salt/reclass/classes/service/")

      def nodes
      if (DEFAULT_GIT_URL.contains("mk-ci")) {
        nodes = sh script: "find /srv/salt/reclass/nodes -name '*.yml' | grep -v 'cfg*.yml'", returnStdout: true
      } else {
        nodes = sh script:"find /srv/salt/reclass/nodes/_generated -name '*.yml' | grep -v 'cfg*.yml'", returnStdout: true
      }
      for (minion in nodes.tokenize()) {
        def basename = sh script: "basename ${minion} .yml", returnStdout: true
        if (!basename.trim().contains(masterName)) {
          testMinion(basename.trim())
        }
      }
    }
  }
}

def testMinion(minionName)
{
  sh("service salt-master restart && service salt-minion restart && sleep 5 && bash -c 'source /srv/salt/scripts/salt-master-init.sh; cd /srv/salt/scripts && verify_salt_minion ${minionName}'")
}