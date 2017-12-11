/**
 *
 * Release MCP
 *
 * Expected parameters:
 *   MCP_VERSION
 *   RELEASE_APTLY
 *   RELEASE_DOCKER
 *   RELEASE_GIT
 *   APTLY_URL
 *   APTLY_STORAGES
 *   DOCKER_CREDENTIALS
 *   DOCKER_URL
 *   DOCKER_IMAGES
 *   GIT_CREDENTIALS
 *   GIT_REPO_LIST
 */

def common = new com.mirantis.mk.Common()

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

def triggerDockerMirrorJob(dockerCredentials, dockerRegistryUrl, dockerRegistry, mcpVersion, imageList) {
  build job: "mirror-docker-images", parameters: [
    [$class: 'StringParameterValue', name: 'TARGET_REGISTRY_CREDENTIALS_ID', value: dockerCredentials],
    [$class: 'StringParameterValue', name: 'REGISTRY_URL', value: dockerRegistryUrl],
    [$class: 'StringParameterValue', name: 'TARGET_REGISTRY', value: dockerRegistry],
    [$class: 'StringParameterValue', name: 'IMAGE_TAG', value: mcpVersion],
    [$class: 'StringParameterValue', name: 'IMAGE_LIST', value: imageList]
  ]
}

def gitRepoAddTag(repoURL, repoName, tag, credentials, ref = "HEAD"){
    git.checkoutGitRepository(repoName, repoURL, "master")
    dir(repoName) {
        def checkTag = sh "git tag -l ${tag}"
        if(checkTag == ""){
            sh 'git tag -a ${tag} ${ref} -m "Release of mcp version ${tag}"'
        }
        sshagent([credentials]) {
            sh "git push origin master ${tag}"
        }
    }
}

node() {
    try {
        if(RELEASE_APTLY.toBoolean())
        {
            stage("Release Aptly"){
                triggerAptlyPromoteJob(APTLY_URL, 'all', false, true, 'all', false, '(.*)/testing', APTLY_STORAGES, '{0}/stable')
                triggerAptlyPromoteJob(APTLY_URL, 'all', false, true, 'all', false, '(.*)/stable', APTLY_STORAGES, '{0}/${MCP_VERSION}')
            }
        }
        if(RELEASE_DOCKER.toBoolean())
        {
            stage("Release Docker"){
                triggerDockerMirrorJob(DOCKER_CREDENTIALS, DOCKER_URL, MCP_VERSION, DOCKER_IMAGES)
            }
        }
        if(RELEASE_GIT.toBoolean())
        {
            stage("Release Git"){
                def repos = GIT_REPO_LIST.tokenize('\n')
                def repoUrl, repoName, repoCommit, repoArray
                for (repo in repos){
                    if(repo.trim().indexOf(' ') == -1){
                        throw new IllegalArgumentException("Wrong format of repository and commit input")
                    }
                    repoArray = repo.trim().tokenize(' ')
                    repoName = repoArray[0]
                    repoUrl = repoArray[1]
                    repoCommit = repoArray[2]
                    gitRepoAddTag(repoUrl, repoName, MCP_VERSION, GIT_CREDENTIALS, repoCommit)
                }
            }
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    }
}