def gerrit = new com.mirantis.mk.Gerrit()
def ssh = new com.mirantis.mk.Ssh()
node("python") {
    ssh.prepareSshAgentKey(CREDENTIALS_ID)
    ssh.ensureKnownHosts(GERRIT_HOST)
    ssh.agentSh(String.format("ssh -p 29418 %s@%s gerrit review --verified +1 %s,%s", GERRIT_USERNAME, GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER))
}
