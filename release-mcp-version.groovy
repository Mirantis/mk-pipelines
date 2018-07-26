/**
 *
 * Promote MCP
 *
 * Expected parameters:
 *   SOURCE_REVISION
 *   TARGET_REVISION
 *   RELEASE_APTLY
 *   RELEASE_DEB_MIRRORS
 *   RELEASE_DOCKER
 *   RELEASE_GIT
 *   APTLY_URL
 *   APTLY_STORAGES
 *   DOCKER_CREDENTIALS
 *   DOCKER_URL
 *   DOCKER_IMAGES
 *   GIT_CREDENTIALS
 *   GIT_REPO_LIST
 *   EMAIL_NOTIFY
 *   NOTIFY_RECIPIENTS
 *   NOTIFY_TEXT
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()

def triggerAptlyPromoteJob(aptlyUrl, components, diffOnly, dumpPublish, packages, recreate, source, storages, target){
  build job: "aptly-promote-all-testing-stable", parameters: [
    [$class: 'StringParameterValue', name: 'APTLY_URL', value: aptlyUrl],
    [$class: 'StringParameterValue', name: 'COMPONENTS', value: components],
    [$class: 'BooleanParameterValue', name: 'DIFF_ONLY', value: diffOnly],
    [$class: 'BooleanParameterValue', name: 'DUMP_PUBLISH', value: dumpPublish],
    [$class: 'StringParameterValue', name: 'PACKAGES', value: packages],
    [$class: 'BooleanParameterValue', name: 'RECREATE', value: recreate],
    [$class: 'StringParameterValue', name: 'SOURCE', value: source],
    [$class: 'StringParameterValue', name: 'STORAGES', value: storages],
    [$class: 'StringParameterValue', name: 'TARGET', value: target]
  ]
}

def triggerDockerMirrorJob(dockerCredentials, dockerRegistryUrl, targetTag, imageList, sourceImageTag) {
  build job: "docker-images-mirror", parameters: [
    [$class: 'StringParameterValue', name: 'TARGET_REGISTRY_CREDENTIALS_ID', value: dockerCredentials],
    [$class: 'StringParameterValue', name: 'REGISTRY_URL', value: dockerRegistryUrl],
    [$class: 'StringParameterValue', name: 'IMAGE_TAG', value: targetTag],
    [$class: 'StringParameterValue', name: 'IMAGE_LIST', value: imageList],
    [$class: 'StringParameterValue', name: 'SOURCE_IMAGE_TAG', value: sourceImageTag]
  ]
}

def triggerMirrorRepoJob(snapshotId, snapshotName) {
  build job: "mirror-snapshot-name-all", parameters: [
    [$class: 'StringParameterValue', name: 'SNAPSHOT_NAME', value: snapshotName],
    [$class: 'StringParameterValue', name: 'SNAPSHOT_ID', value: snapshotId]
  ]
}

def triggerGitTagJob(gitRepoList, gitCredentials, tag, source_tag) {
  build job: "tag-git-repos-stable", parameters: [
    [$class: 'StringParameterValue', name: 'GIT_REPO_LIST', value: gitRepoList],
    [$class: 'StringParameterValue', name: 'GIT_CREDENTIALS', value: gitCredentials],
    [$class: 'StringParameterValue', name: 'TAG', value: tag],
    [$class: 'StringParameterValue', name: 'SOURCE_TAG', value: source_tag],
  ]
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            stage("Promote"){
                if(RELEASE_APTLY.toBoolean())
                {
                    common.infoMsg("Promoting Aptly")
                    triggerAptlyPromoteJob(APTLY_URL, 'all', false, true, 'all', false, "(.*)/${SOURCE_REVISION}", APTLY_STORAGES, "{0}/${TARGET_REVISION}")
                }

                if(RELEASE_DEB_MIRRORS.toBoolean()){
                    common.infoMsg("Promoting Debmirrors")
                    triggerMirrorRepoJob(SOURCE_REVISION, TARGET_REVISION)
                }

                if(RELEASE_DOCKER.toBoolean())
                {
                    common.infoMsg("Promoting Docker images")
                    triggerDockerMirrorJob(DOCKER_CREDENTIALS, DOCKER_URL, TARGET_REVISION, DOCKER_IMAGES, SOURCE_REVISION)
                }

                if(RELEASE_GIT.toBoolean())
                {
                    common.infoMsg("Promoting Git repositories")
                    triggerGitTagJob(GIT_REPO_LIST, GIT_CREDENTIALS, TARGET_REVISION)

                }
                if (EMAIL_NOTIFY.toBoolean()) {
                    emailext(to: NOTIFY_RECIPIENTS,
                        body: NOTIFY_TEXT,
                        subject: "MCP Promotion has been done")
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}