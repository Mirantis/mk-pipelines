
/**
 * Docker cleanup pipeline which can kill old containers (more than a day) and prune docker itself
 *
 *  SALT_MASTER_URL
 *  SALT_MASTER_CREDENTIALS
 *  TEST_DATE_STRING - string representation of date which will be used for delete matching (ie. yesterday)
 */
common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
def python = new com.mirantis.mk.Python()

def pepperEnv = "pepperEnv"
timeout(time: 12, unit: 'HOURS') {
  node{
    def saltMaster;
    stage('Setup virtualenv for Pepper') {
      python.setupPepperVirtualenv(pepperEnv, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }
    stage("Clean old containers"){
      salt.cmdRun(pepperEnv, 'I@jenkins:slave', """
          docker ps --format='{{.ID}}' | xargs -n 1 -r docker inspect \\
          -f '{{.ID}} {{.State.Running}} {{.State.StartedAt}}' \\
          | awk '\$2 == "true" && \$3 <= "'\$(date -d '${TEST_DATE_STRING}' -Ins --utc \\
          | sed 's/+0000/Z/')'" { print \$1 }' \\
          | xargs -r docker rm -f
          """, false)
    }
    stage("Run docker system prune"){
      salt.cmdRun(pepperEnv, 'I@jenkins:slave', "docker system prune --all --force", false) // dont verify the result
    }
  }
}
