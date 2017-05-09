
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

def testNode(node, basename, saltOpts) {
  sh("reclass --nodeinfo ${node} >/dev/null")
  sh("salt-call ${saltOpts} --id=${basename} state.show_top")
  sh("salt-call ${saltOpts} --id=${basename} state.show_lowstate >/dev/null")
}

def testMaster(masterHostname) {

  def img = docker.image("ubuntu:xenial")
  def saltOpts = "--retcode-passthrough --force-color"

  img.inside {
    sh("apt-get update; apt-get install -y wget")
    sh("echo 'deb [arch=amd64] http://apt-mk.mirantis.com/${DIST}/ nightly salt salt-latest' > /etc/apt/sources.list.d/apt-mk.list")
    sh("wget -O - http://apt-mk.mirantis.com/public.gpg | apt-key add -")
    sh("apt-get update; apt-get install -y salt-master python-psutil iproute2 curl python-dev python-pip salt-formula-* python-sphinx")
    sh("pip install -U https://github.com/madduck/reclass/archive/master.zip")
    sh("mkdir -p /etc/salt/grains.d && touch /etc/salt/grains.d/dummy")
    sh("[ ! -d /etc/salt/pki/minion ] && mkdir -p /etc/salt/pki/minion")
    sh("[ ! -d /etc/salt/master.d ] && mkdir -p /etc/salt/master.d || true")
    def masterConf = """file_roots
:  base:
    - /usr/share/salt-formulas/env
pillar_opts: False
open_mode: True
reclass: &reclass
  storage_type: yaml_fs
  inventory_base_uri: /srv/salt/reclass
ext_pillar:
  - reclass: *reclass
master_tops:
  reclass: *reclass"""
    writeFile file: "/etc/salt/master.d/master.conf", text: masterConf

    sh("[ -d /srv/salt/reclass/classes/service ] || mkdir -p /srv/salt/reclass/classes/service || true")
    sh("""for i in /usr/share/salt-formulas/reclass/service/*; do
        [ -e /srv/salt/reclass/classes/service/\$(basename \$i) ] || ln -s \$i /srv/salt/reclass/classes/service/\$(basename \$i)
    done""")
    def jenkinsUID = common.getJenkinsUid()
    def jenkinsGID = common.getJenkinsGid()
    sh("chown -R ${jenkinsUID}:${jenkinsGID} /srv/salt/reclass/classes/service")
    sh("[ ! -d /etc/reclass ] && mkdir /etc/reclass || true")
    def reclassConfig = """storage_type: yaml_fs
pretty_print: True
output: yaml
inventory_base_uri: /srv/salt/reclass"""
    writeFile file: "/etc/reclass/reclass-config.yml", text: reclassConfig
    sh("usr/bin/salt-master; sleep 3")
    sh("salt-call saltutil.sync_all")
    sh("reclass --nodeinfo ${masterHostname} >/dev/null")
    sh("salt-call ${saltOpts} state.show_top")


    testSteps = [:]
    def nodes = sh script: "find /srv/salt/reclass/nodes -type f -name *.yml ! -name cfg*", returnStdout: true
    for (reclassNode in nodes.tokenize()) {
      basename = sh script: "\$(basename $node .yml)", returnStdout: true
      testSteps[basename] = { testNode(reclassNode, basename, saltOpts) }
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
      nodes = findFiles(glob: "cfg*.yml")
    }

    for (int i = 0; i < nodes.size(); ++i) {
      stage(nodes[i].getName()) {
        testMaster(nodes[i] - '.yml')
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
