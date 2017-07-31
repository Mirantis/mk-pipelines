
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

node{
  def saltMaster;
  stage("Connect to MCP salt master"){
    saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
  }
  stage("Clean old containers"){
    salt.cmdRun(saltMaster, 'I@jenkins:slave', """
        docker ps --format='{{.ID}}' | xargs -n 1 -r docker inspect \\
        -f '{{.ID}} {{.State.Running}} {{.State.StartedAt}}' \\
        | awk '\$2 == "true" && \$3 <= "'\$(date -d '${TEST_DATE_STRING}' -Ins --utc \\
        | sed 's/+0000/Z/')'" { print \$1 }' \\
        | xargs -r docker rm -f
        """, false)
  }
  stage("Run docker system prune"){
    salt.cmdRun(saltMaster, 'I@jenkins:slave', "docker system prune -f")
  }
}
