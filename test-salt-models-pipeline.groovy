
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

def testMinion(minion, saltOpts)
{
  sh("reclass-salt -p ${minion} >  /tmp/${minion}.pillar_verify")
}

def setupandtest(master) {
  def img = docker.image("ubuntu:trusty")
  def saltOpts = "--retcode-passthrough --force-color"

  img.inside("-u root:root") {
sh("apt-get update; apt-get install  software-properties-common   python-software-properties -y")
    sh("add-apt-repository ppa:saltstack/salt -y")
    sh("apt-get update; apt-get install -y curl subversion git python-pip sudo")
    sh("sudo apt-get install -y salt-master salt-minion salt-ssh salt-cloud salt-doc")
    sh("svn export --force https://github.com/chnyda/salt-formulas/trunk/deploy/scripts /srv/salt/scripts")
    //configure git
    sh("git config --global user.email || git config --global user.email 'ci@ci.local'")
    sh("git config --global user.name  || git config --global user.name 'CI'")
    sh("mkdir -p /srv/salt/reclass; cp -r * /srv/salt/reclass")
    //
//    sh("cd /srv/salt/reclass; test ! -e .gitmodules || git submodule update --init --recursive")
//    sh("cd /srv/salt/reclass; git commit -am 'Fake branch update' || true") 

    // setup iniot and verify salt master and minions
    sh(""". /srv/salt/scripts/salt-master-init.sh
        export SUDO=sudo
        export DEBUG=1
        export MASTER_HOSTNAME=${master}
        system_config;
        saltmaster_bootstrap &&\
        saltmaster_init > /tmp/${master}.init &&\
        verify_salt_master
      """)

    testSteps = [:]
    nodes = sh script:"ls /srv/salt/reclass/nodes/_generated"
    for (minion in nodes.tokenize()) {
      def basename = sh script: "basename ${minion} .yml", returnStdout: true
      testSteps = { testMinion(basename)}
    }
    parallel testSteps

  }

}

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

    def nodes
    dir ('nodes') {
      nodes = sh script: "find -type f -name cfg*.yml", returnStdout: true
    }

    stage("test") {
      for (masterNode in nodes.tokenize()) {
        basename = sh script: "basename ${masterNode} .yml", returnStdout: true
        setupandtest(basename)
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