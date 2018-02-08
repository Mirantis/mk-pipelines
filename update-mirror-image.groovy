/**
 * Update mirror image
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [https://10.10.10.1:8000].
 *   UPDATE_APTLY                       Option to update Aptly
 *   UPDATE_APTLY_MIRRORS               List of mirrors
 *   PUBLISH_APTLY                      Publish aptly snapshots
 *   RECREATE_APTLY_PUBLISHES           Option to recreate Aptly publishes separated by comma
 *   FORCE_OVERWRITE_APTLY_PUBLISHES    Option to force overwrite existing packages while publishing
 *   CLEANUP_APTLY                      Option to cleanup old Aptly snapshots
 *   UPDATE_DOCKER_REGISTRY             Option to update Docker Registry
 *   CLEANUP_DOCKER_CACHE               Option to cleanup locally cached Docker images
 *   UPDATE_PYPI                        Option to update Python Packages
 *   UPDATE_GIT                         Option to update Git repositories
 *   UPDATE_IMAGES                      Option to update VM images
 *
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def python = new com.mirantis.mk.Python()
def venvPepper = "venvPepper"
timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            if(UPDATE_APTLY.toBoolean()){
                stage('Update Aptly mirrors'){
                    def aptlyMirrorArgs = "-s -v"

                    salt.enforceState(venvPepper, '*apt*', ['aptly.server'], true)
                    sleep(10)

                    if(UPDATE_APTLY_MIRRORS != ""){
                        common.infoMsg("Updating List of Aptly mirrors.")
                        UPDATE_APTLY_MIRRORS = UPDATE_APTLY_MIRRORS.replaceAll("\\s","")
                        def mirrors = UPDATE_APTLY_MIRRORS.tokenize(",")
                        for(mirror in mirrors){
                            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=\"${aptlyMirrorArgs} -m ${mirror}\"", 'runas=aptly'], null, true)
                        }
                    }
                    else{
                        common.infoMsg("Updating all Aptly mirrors.")
                        salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=\"${aptlyMirrorArgs}\"", 'runas=aptly'], null, true)
                    }
                }
            }
            if(PUBLISH_APTLY.toBoolean()){
                def aptlyPublishArgs = "-av"

                common.infoMsg("Publishing all Aptly snapshots.")

                salt.enforceState(venvPepper, '*apt*', ['aptly.publisher'], true)
                sleep(10)

                if(CLEANUP_APTLY.toBoolean()){
                    aptlyPublishArgs += "c"
                }
                if(RECREATE_APTLY_PUBLISHES.toBoolean()){
                    aptlyPublishArgs += "r"
                }
                if(FORCE_OVERWRITE_APTLY_PUBLISHES.toBoolean()){
                    aptlyPublishArgs += "f"
                }
                salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=\"${aptlyPublishArgs}\"", 'runas=aptly'], null, true)
            }
            if(UPDATE_DOCKER_REGISTRY.toBoolean()){
                stage('Update Docker images'){
                    common.infoMsg("Updating Docker images.")
                    salt.enforceState(venvPepper, '*apt*', 'docker.client.registry')
                    if(CLEANUP_DOCKER_CACHE.toBoolean()){
                        salt.cmdRun(venvPepper, '*apt*', 'docker system prune --all --force')
                    }
                }
            }
            if(UPDATE_PYPI.toBoolean()){
                stage('Update PyPi packages'){
                    common.infoMsg("Updating PyPi packages.")
                    salt.cmdRun(venvPepper, '*apt*', 'pip2pi /srv/pypi_mirror/packages/ -r /srv/pypi_mirror/requirements.txt')
                }
            }
            if(UPDATE_GIT.toBoolean()){
                stage('Update Git repositories'){
                    common.infoMsg("Updating Git repositories.")
                    salt.enforceState(venvPepper, '*apt*', ['git.server'], true)
                }
            }
            if(UPDATE_IMAGES.toBoolean()){
                stage('Update VM images'){
                    common.infoMsg("Updating VM images.")
                    salt.runSaltProcessStep(venvPepper, '*apt*', '/srv/scripts/update-images.sh')
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
