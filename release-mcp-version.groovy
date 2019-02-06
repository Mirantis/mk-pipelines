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
 *   VCP_IMAGE_LIST - list of images
 *   SYNC_VCP_IMAGE_TO_S3 - boolean
 *   RELEASE_VCP_IMAGES - boolean
 *   EMAIL_NOTIFY
 *   NOTIFY_RECIPIENTS
 *   PKG_REPO_LIST
 *
 */

common = new com.mirantis.mk.Common()

syncVcpImagesToS3 = env.SYNC_VCP_IMAGE_TO_S3 ?: false
emailNotify = env.EMAIL_NOTIFY ?: false

def triggerAptlyPromoteJob(aptlyUrl, components, diffOnly, dumpPublish, packages, recreate, source, storages, target) {
    build job: "aptly-promote-all-testing-stable", parameters: [
        [$class: 'StringParameterValue', name: 'APTLY_URL', value: aptlyUrl],
        [$class: 'StringParameterValue', name: 'COMPONENTS', value: components],
        [$class: 'BooleanParameterValue', name: 'DIFF_ONLY', value: diffOnly],
        [$class: 'BooleanParameterValue', name: 'DUMP_PUBLISH', value: dumpPublish],
        [$class: 'StringParameterValue', name: 'PACKAGES', value: packages],
        [$class: 'BooleanParameterValue', name: 'RECREATE', value: recreate],
        [$class: 'StringParameterValue', name: 'SOURCE', value: source],
        [$class: 'StringParameterValue', name: 'STORAGES', value: storages],
        [$class: 'StringParameterValue', name: 'TARGET', value: target],
    ]
}

def triggerDockerMirrorJob(dockerCredentials, dockerRegistryUrl, targetTag, imageList, sourceImageTag) {
    build job: "docker-images-mirror", parameters: [
        [$class: 'StringParameterValue', name: 'TARGET_REGISTRY_CREDENTIALS_ID', value: dockerCredentials],
        [$class: 'StringParameterValue', name: 'REGISTRY_URL', value: dockerRegistryUrl],
        [$class: 'StringParameterValue', name: 'IMAGE_TAG', value: targetTag],
        [$class: 'TextParameterValue', name: 'IMAGE_LIST', value: imageList],
        [$class: 'StringParameterValue', name: 'SOURCE_IMAGE_TAG', value: sourceImageTag],
    ]
}

def triggerMirrorRepoJob(snapshotId, snapshotName) {
    build job: "mirror-snapshot-name-all", parameters: [
        [$class: 'StringParameterValue', name: 'SNAPSHOT_NAME', value: snapshotName],
        [$class: 'StringParameterValue', name: 'SNAPSHOT_ID', value: snapshotId],
    ]
}

def triggerEbfRepoJob(snapshotId, snapshotName) {
    build job: "ebf-snapshot-name-all", parameters: [
        [$class: 'StringParameterValue', name: 'SNAPSHOT_NAME', value: snapshotName],
        [$class: 'StringParameterValue', name: 'SNAPSHOT_ID', value: snapshotId],
    ]
}

def triggerGitTagJob(gitRepoList, gitCredentials, tag, sourceTag) {
    // There is no `nightly` and `testing` build-IDs` in release process
    // for git repos
    if (sourceTag in ['nightly', 'testing']) {
        sourceTag = 'master'
    }
    build job: "tag-git-repos-all", parameters: [
        [$class: 'TextParameterValue', name: 'GIT_REPO_LIST', value: gitRepoList],
        [$class: 'StringParameterValue', name: 'GIT_CREDENTIALS', value: gitCredentials],
        [$class: 'StringParameterValue', name: 'TAG', value: tag],
        [$class: 'StringParameterValue', name: 'SOURCE_TAG', value: sourceTag],
    ]
}

def triggerPromoteVCPJob(VcpImageList, tag, sourceTag) {
    build job: "promote-vcp-images-all", parameters: [
        [$class: 'TextParameterValue', name: 'VCP_IMAGE_LIST', value: VcpImageList],
        [$class: 'StringParameterValue', name: 'TAG', value: tag],
        [$class: 'StringParameterValue', name: 'SOURCE_TAG', value: sourceTag],
        [$class: 'BooleanParameterValue', name: 'FORCE_OVERWRITE', value: true],
    ]
}

def triggerPkgPromoteJob(PkgRepoList, PromoteFrom, PromoteTo) {
    //For repositories with per-package promote such as extra, ceph
    //we use different approaches for different steps of promoting
    def repos = PkgRepoList.trim().tokenize("\n")
    def RepoName, RepoDist, PackagesToPromote
    for (repo in repos) {
        if (repo.startsWith('#')) {
            common.warningMsg("Skipping repo ${repo}")
            continue
        }
        if (repo.trim().indexOf(' ') == -1) {
            throw new IllegalArgumentException("Wrong format of repository and commit input")
        }
        repoArray = repo.trim().tokenize(' ')
        RepoName = repoArray[0]
        RepoDist = repoArray[1]
        PackagesToPromote = repoArray[2]
        //During promote from testing to proposed we use per-package promote
        if (SOURCE_REVISION == 'testing') {
            build job: "pkg-promote", parameters: [
               [$class: 'StringParameterValue', name: 'repoName', value: RepoName],
               [$class: 'StringParameterValue', name: 'repoDist', value: RepoDist],
               [$class: 'StringParameterValue', name: 'promoteFrom', value: PromoteFrom],
               [$class: 'StringParameterValue', name: 'promoteTo', value: PromoteTo],
               [$class: 'TextParameterValue', name: 'packagesToPromote', value: PackagesToPromote],
            ]
        //In promote from proposed to release we move links to snapshots
        } else if (SOURCE_REVISION == 'proposed') {
            build job: "mirror-snapshot-pkg-name-${RepoName}-${RepoDist}", parameters: [
               [$class: 'StringParameterValue', name: 'Snapshot_Name', value: PromoteFrom],
               [$class: 'StringParameterValue', name: 'Snapshot_Id', value: PromoteTo],
            ]
        }
    }
}

def triggerSyncVCPJob(VcpImageList, targetTag) {
    // Operation must be synced with triggerPromoteVCPJob procedure!
    def images = VcpImageList.trim().tokenize()
    TargetVcpImageList = ''
    for (image in images) {
        if (image.startsWith('#')) {
            common.warningMsg("Skipping image ${image}")
            continue
        }
        common.infoMsg("Replacing SUBS_SOURCE_VCP_IMAGE_TAG => ${targetTag}")
        TargetVcpImageList += image.replace('SUBS_SOURCE_VCP_IMAGE_TAG', targetTag) + '\n' +
            image.replace('SUBS_SOURCE_VCP_IMAGE_TAG', targetTag).trim() + '.md5' + '\n'

    }
    build job: "upload_images_to_s3", parameters: [
        [$class: 'TextParameterValue', name: 'FILENAMES',
         value : TargetVcpImageList]
    ]
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            stage("Promote") {
                if (RELEASE_APTLY.toBoolean()) {
                    common.infoMsg("Promoting Aptly")
                    triggerAptlyPromoteJob(APTLY_URL, 'all', false, true, 'all', false, "(.*)/${SOURCE_REVISION}", APTLY_STORAGES, "{0}/${TARGET_REVISION}")
                }

                if (PKG_PROMOTE.toBoolean()) {
                    common.infoMsg("Promoting Extra and Ceph packages")
                    triggerPkgPromoteJob(PKG_REPO_LIST, SOURCE_REVISION, TARGET_REVISION)
                }

                if (RELEASE_DEB_MIRRORS.toBoolean()) {
                    common.infoMsg("Promoting Debmirrors")
                    triggerMirrorRepoJob(SOURCE_REVISION, TARGET_REVISION)
                }

                if (RELEASE_EBF_MIRRORS.toBoolean()) {
                    common.infoMsg("Promoting Emergency Bug Fix Debmirrors")
                    triggerEbfRepoJob(SOURCE_REVISION, TARGET_REVISION)
                }

                if (RELEASE_DOCKER.toBoolean()) {
                    common.infoMsg("Promoting Docker images")
                    triggerDockerMirrorJob(DOCKER_CREDENTIALS, DOCKER_URL, TARGET_REVISION, DOCKER_IMAGES, SOURCE_REVISION)
                }

                if (RELEASE_GIT.toBoolean()) {
                    common.infoMsg("Promoting Git repositories")
                    triggerGitTagJob(GIT_REPO_LIST, GIT_CREDENTIALS, TARGET_REVISION, SOURCE_REVISION)

                }
                if (RELEASE_VCP_IMAGES.toBoolean()) {
                    common.infoMsg("Promoting VCP images")
                    triggerPromoteVCPJob(VCP_IMAGE_LIST, TARGET_REVISION, SOURCE_REVISION)

                }
                if (syncVcpImagesToS3.toBoolean()) {
                    common.infoMsg("Syncing VCP images from internal: http://images.mcp.mirantis.net/ to s3: images.mirantis.com")
                    triggerSyncVCPJob(VCP_IMAGE_LIST, TARGET_REVISION)
                }
                if (emailNotify) {
                    notify_text = "MCP Promotion  ${env.SOURCE_REVISION} => ${env.TARGET_REVISION} has been done"
                    emailext(to: NOTIFY_RECIPIENTS,
                        body: notify_text,
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
