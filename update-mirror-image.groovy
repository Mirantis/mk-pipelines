/**
 * Update local mirror
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [https://10.10.10.1:6969].
 *   UPDATE_APTLY                       Option to update Aptly
 *   UPDATE_APTLY_MIRRORS               List of mirrors
 *   PUBLISH_APTLY                      Publish aptly snapshots
 *   RECREATE_APTLY_MIRRORS             Recreate Aptly mirrors
 *   RECREATE_APTLY_PUBLISHES           Option to recreate Aptly publishes separated by comma
 *   FORCE_OVERWRITE_APTLY_PUBLISHES    Option to force overwrite existing packages while publishing
 *   CLEANUP_APTLY                      Option to cleanup old Aptly snapshots
 *   UPDATE_DEBMIRRORS                  Option to update Debmirrors
 *   UPDATE_DOCKER_REGISTRY             Option to update Docker Registry
 *   CLEANUP_DOCKER_CACHE               Option to cleanup locally cached Docker images
 *   UPDATE_GIT                         Option to update Git repositories
 *   UPDATE_FILES                       Option to update static files
 *
**/

common = new com.mirantis.mk.Common()
salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()
venvPepper = "venvPepper"

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            def engine = salt.getPillar(venvPepper, 'I@aptly:server', "aptly:server:source:engine")
            runningOnDocker = engine.get("return")[0].containsValue("docker")

            if(UPDATE_APTLY.toBoolean()){
                stage('Update Aptly mirrors'){
                    def aptlyMirrorArgs = "-s -v"

                    if(RECREATE_APTLY_MIRRORS.toBoolean())
                    {
                        if(runningOnDocker){
                            salt.cmdRun(venvPepper, 'I@aptly:server', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true)
                        }
                        else{
                            salt.cmdRun(venvPepper, 'I@aptly:server', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true, ['runas=aptly'])
                        }
                    }

                    salt.enforceState(venvPepper, 'I@aptly:server', ['aptly.server'], true)
                    sleep(10)

                    if(UPDATE_APTLY_MIRRORS != ""){
                        common.infoMsg("Updating List of Aptly mirrors.")
                        UPDATE_APTLY_MIRRORS = UPDATE_APTLY_MIRRORS.replaceAll("\\s","")
                        def mirrors = UPDATE_APTLY_MIRRORS.tokenize(",")
                        for(mirror in mirrors){
                            if(runningOnDocker){
                                salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=\"${aptlyMirrorArgs} -m ${mirror}\""], null, true)
                            }else{
                                salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=\"${aptlyMirrorArgs} -m ${mirror}\"", 'runas=aptly'], null, true)
                            }
                        }
                    }
                    else{
                        common.infoMsg("Updating all Aptly mirrors.")

                        if(runningOnDocker){
                            salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=\"${aptlyMirrorArgs}\""], null, true)
                        }
                        else{
                            salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=\"${aptlyMirrorArgs}\"", 'runas=aptly'], null, true)
                        }
                    }
                }
            }
            if(PUBLISH_APTLY.toBoolean()){
                def aptlyPublishArgs = "-v"

                common.infoMsg("Publishing all Aptly snapshots.")

                salt.enforceState(venvPepper, 'I@aptly:server', ['aptly.publisher'], true)
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
                if(runningOnDocker){
                    aptlyPublishArgs += " -u http://10.99.0.1:8080"
                    salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=\"${aptlyPublishArgs}\""], null, true)
                }
                else{
                    aptlyPublishArgs += "a"
                    salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=\"${aptlyPublishArgs}\"", 'runas=aptly'], null, true)
                }
            }
            if(UPDATE_DEBMIRRORS.toBoolean()){
                stage('Update Debmirrors'){
                    common.infoMsg("Updating Debmirrors")
                    salt.enforceState(venvPepper, 'I@debmirror:client', 'debmirror')
                }
            }
            if(UPDATE_DOCKER_REGISTRY.toBoolean()){
                stage('Update Docker images'){
                    common.infoMsg("Updating Docker images.")
                    salt.enforceState(venvPepper, 'I@aptly:server', 'docker.client.registry')
                    if(CLEANUP_DOCKER_CACHE.toBoolean()){
                        salt.cmdRun(venvPepper, 'I@aptly:server', 'docker system prune --all --force')
                    }
                }
            }
            if(UPDATE_GIT.toBoolean()){
                stage('Update Git repositories'){
                    common.infoMsg("Updating Git repositories.")
                    salt.enforceState(venvPepper, 'I@aptly:server', ['git.server'], true)
                }
            }
            if(UPDATE_FILES.toBoolean()){
                stage('Update static files'){
                    common.infoMsg("Updating static files.")
                    salt.enforceState(venvPepper, 'I@aptly:server', ['linux.system.file'], true)
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
