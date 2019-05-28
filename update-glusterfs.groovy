/**
 * Complete update glusterfs pipeline
 *
 * Expected parameters:
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_CREDENTIALS              Credentials to the Salt API
 *     SALT_MASTER_URL                      Full Salt API address [https://10.10.10.1:8000]
 */

// Convert parameters from yaml to env variables
params = readYaml text: env.DRIVE_TRAIN_PARAMS
for (key in params.keySet()) {
  value = params[key]
  env.setProperty(key, value)
}

def waitGerrit(salt_target, wait_timeout) {
  def salt = new com.mirantis.mk.Salt()
  def common = new com.mirantis.mk.Common()
  def python = new com.mirantis.mk.Python()
  def pEnv = "pepperEnv"
  python.setupPepperVirtualenv(pEnv, env.SALT_MASTER_URL, env.SALT_MASTER_CREDENTIALS)

  salt.fullRefresh(pEnv, salt_target)

  def gerrit_master_url = salt.getPillar(pEnv, salt_target, '_param:gerrit_master_url')

  if(!gerrit_master_url['return'].isEmpty()) {
    gerrit_master_url = gerrit_master_url['return'][0].values()[0]
  } else {
    gerrit_master_url = ''
  }

  if (gerrit_master_url != '') {
    common.infoMsg('Gerrit master url "' + gerrit_master_url + '" retrieved at _param:gerrit_master_url')
  } else {
    common.infoMsg('Gerrit master url could not be retrieved at _param:gerrit_master_url. Falling back to gerrit pillar')

    def gerrit_host
    def gerrit_http_port
    def gerrit_http_scheme
    def gerrit_http_prefix

    def host_pillar = salt.getPillar(pEnv, salt_target, 'gerrit:client:server:host')
    gerrit_host = salt.getReturnValues(host_pillar)

    def port_pillar = salt.getPillar(pEnv, salt_target, 'gerrit:client:server:http_port')
    gerrit_http_port = salt.getReturnValues(port_pillar)

    def scheme_pillar = salt.getPillar(pEnv, salt_target, 'gerrit:client:server:protocol')
    gerrit_http_scheme = salt.getReturnValues(scheme_pillar)

    def prefix_pillar = salt.getPillar(pEnv, salt_target, 'gerrit:client:server:url_prefix')
    gerrit_http_prefix = salt.getReturnValues(prefix_pillar)

    gerrit_master_url = gerrit_http_scheme + '://' + gerrit_host + ':' + gerrit_http_port + gerrit_http_prefix

  }

  timeout(wait_timeout) {
    common.infoMsg('Waiting for Gerrit to come up..')
    def check_gerrit_cmd = 'while true; do curl -sI -m 3 -o /dev/null -w' + " '" + '%{http_code}' + "' " + gerrit_master_url + '/ | grep 200 && break || sleep 1; done'
    salt.cmdRun(pEnv, salt_target, 'timeout ' + (wait_timeout*60+3) + ' /bin/sh -c -- ' + '"' + check_gerrit_cmd + '"')
  }
}

def waitJenkins(salt_target, wait_timeout) {
  def salt = new com.mirantis.mk.Salt()
  def common = new com.mirantis.mk.Common()
  def python = new com.mirantis.mk.Python()
  def pEnv = "pepperEnv"
  python.setupPepperVirtualenv(pEnv, env.SALT_MASTER_URL, env.SALT_MASTER_CREDENTIALS)

  salt.fullRefresh(pEnv, salt_target)

  // Jenkins
  def jenkins_master_host = salt.getReturnValues(salt.getPillar(pEnv, salt_target, '_param:jenkins_master_host'))
  def jenkins_master_port = salt.getReturnValues(salt.getPillar(pEnv, salt_target, '_param:jenkins_master_port'))
  def jenkins_master_protocol = salt.getReturnValues(salt.getPillar(pEnv, salt_target, '_param:jenkins_master_protocol'))
  def jenkins_master_url_prefix = salt.getReturnValues(salt.getPillar(pEnv, salt_target, '_param:jenkins_master_url_prefix'))
  jenkins_master_url = "${jenkins_master_protocol}://${jenkins_master_host}:${jenkins_master_port}${jenkins_master_url_prefix}"

  timeout(wait_timeout) {
    common.infoMsg('Waiting for Jenkins to come up..')
    def check_jenkins_cmd = 'while true; do curl -sI -m 3 -o /dev/null -w' + " '" + '%{http_code}' + "' " + jenkins_master_url + '/whoAmI/ | grep 200 && break || sleep 1; done'
    salt.cmdRun(pEnv, salt_target, 'timeout ' + (wait_timeout*60+3) + ' /bin/sh -c -- ' + '"' + check_jenkins_cmd + '"')
  }
}

node() {
  stage('Update glusterfs servers') {
    build(job: 'update-glusterfs-servers')
  }
  sleep 180
  stage('Update glusterfs clients') {
    build(job: 'update-glusterfs-clients')
  }
  waitJenkins('I@jenkins:client', 300)
  waitGerrit('I@gerrit:client', 300)
  stage('Update glusterfs cluster.op-version') {
    build(job: 'update-glusterfs-cluster-op-version')
  }
}
