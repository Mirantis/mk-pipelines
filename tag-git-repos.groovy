/**
 *
 * Tag Git repositories
 *
 * Expected parameters:
 *   GIT_REPO_LIST
 *   GIT_CREDENTIALS
 *   TAG
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()

def gitRepoAddTag(repoURL, repoName, tag, credentials, ref = "HEAD"){
    git.checkoutGitRepository(repoName, repoURL, "master", credentials)
    dir(repoName) {
        def checkTag = sh(script: "git tag -l ${tag}", returnStdout: true)
        if(checkTag == ""){
            sh "git tag -a ${tag} ${ref} -m \"Release of mcp version ${tag}\""
        }else{
            def currentTagRef = sh(script: "git rev-list -n 1 ${tag}", returnStdout: true)
            if(currentTagRef.equals(ref)){
                common.infoMsg("Tag is already on the right ref")
                return
            }
            else{
                sshagent([credentials]) {
                    sh "git push --delete origin ${tag}"
                }
                sh "git tag --delete ${tag}"
                sh "git tag -a ${tag} ${ref} -m \"Release of mcp version ${tag}\""
            }
        }
        sshagent([credentials]) {
            sh "git push origin ${tag}"
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
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
                gitRepoAddTag(repoUrl, repoName, TAG, GIT_CREDENTIALS, repoCommit)
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}