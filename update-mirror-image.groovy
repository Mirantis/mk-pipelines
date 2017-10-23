/**
 * Update mirror image
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API.
 *   SALT_MASTER_URL            Full Salt API address [https://10.10.10.1:8000].
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def venvPepper = "venvPepper"

node() {
    try {

        python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

        stage('Update Aptly packages'){
            common.infoMsg("Updating Aptly packages.")
            salt.enforceState(venvPepper, 'I@aptly:server', ['aptly'], true)
            salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.run', ['/srv/scripts/aptly-update.sh'], null, true)
        }

        stage('Update Docker images'){
            common.infoMsg("Updating Docker images.")
            salt.enforceState(venvPepper, 'I@aptly:server', ['docker.client.registry'], true)
        }

        stage('Update PyPi packages'){
            common.infoMsg("Updating PyPi packages.")
            salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.run', ['pip2pi /srv/pypi_mirror/packages/ -r /srv/pypi_mirror/requirements.txt'], null, true)
        }

        stage('Update Git repositories'){
            common.infoMsg("Updating Git repositories.")
            salt.enforceState(venvPepper, 'I@aptly:server', ['git.server'], true)
        }

        stage('Update VM images'){
            common.infoMsg("Updating VM images.")
            salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.run', ['/srv/scripts/update-images.sh'], null, true)
        }

    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
        throw e
    }
}