/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL            Salt API server location
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   MCP_VERSION                Version of MCP to upgrade to
 *   UPDATE_LOCAL_REPOS         Update local repositories
 */

// Load shared libs
def salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
def venvPepper = "venvPepper"
timeout(time: 12, unit: 'HOURS') {
    node("python") {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            stage("Update Reclass"){
                common.infoMsg("Updating reclass model")
                salt.cmdRun(venvPepper, "I@salt:master", 'cd /srv/salt/reclass && git pull -r && git submodule update', false)
                salt.cmdRun(venvPepper, 'I@salt:master', 'reclass-salt --top')
                salt.enforceState(venvPepper, "I@salt:master", 'reclass', true)
            }

            if(UPDATE_LOCAL_REPOS.toBoolean()){
                stage("Update local repos"){
                    common.infoMsg("Updating local repositories")
                    salt.cmdRun(venvPepper, '*apt*', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true, ['runas=aptly'])
                    salt.enforceState(venvPepper, '*apt*', 'aptly', true)
                    salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", 'runas=aptly'], null, true)
                    salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-afrv", 'runas=aptly'], null, true)

                    salt.enforceState(venvPepper, '*apt*', 'docker.client.registry', true)

                    salt.cmdRun(venvPepper, '*apt*', "export HOME='/root';export MCP_VERSION='${MCP_VERSION}';/srv/scripts/debmirror.sh")

                    salt.enforceState(venvPepper, '*apt*', 'git server', true)

                    salt.cmdRun(venvPepper, '*apt*', 'pip2pi /srv/pypi_mirror/packages/ -r /srv/pypi_mirror/requirements.txt')

                    salt.cmdRun(venvPepper, '*apt*', '/srv/scripts/update-images.sh')
                }
            }

            stage("Update APT repos"){
                common.infoMsg("Updating APT repositories")
                salt.enforceState(venvPepper, "I@linux:system", 'linux.system.repo', true)
            }

            stage("Update formulas"){
                common.infoMsg("Updating salt formulas")
                salt.cmdRun(venvPepper, "I@salt:master", 'apt-get clean && apt-get update && apt-get install -y salt-formula-*')

                common.infoMsg("Running salt sync-all")
                salt.runSaltProcessStep(venvPepper, '*', 'saltutil.sync_all', [], null, true)
            }
        }
        catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
