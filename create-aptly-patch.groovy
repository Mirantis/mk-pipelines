/**
 *
 * Create Aptly patch
 *
 * Expected parameters:
 * REPO_LIST - YAML with structure of repositories.
 *           Example: mcp-updates-2018.1-xenial:
 *                      url: http://apt.mirantis.com/xenial
 *                      keyurl: http://apt.mirantis.com/public.gpg
 *                      distribution: nightly
 *                      component: salt
 *                      packages: all
 *                    mcp-updates-2017.12-xenial:
 *                      url: http://apt.mirantis.com/xenial
 *                      distribution: "2017.12"
 *                      component: salt
 *                      insecure: true
 *                      packages:
 *                        - salt-formula-salt
 *                        - salt-formula-nginx
 * UPLOAD_URL - URL of an WebDAV used to upload the image after creating.
 * PATCH_NAME - Name of file which will be uploaded to UPLOAD_URL.
 *
 */

common = new com.mirantis.mk.Common()
img = null
timeout(time: 12, unit: 'HOURS') {
    node(){
        try {
            def reposYaml = readYaml text:"${REPO_LIST}"
            def repos = reposYaml.values()
            def insecure = ""
            img = docker.image("ubuntu:xenial")
            img.pull()

            img.inside("-u root:root --hostname=apt-mirror --ulimit nofile=4096:8192") {
                stage("Create patch"){
                    sh("apt-get update;apt-get install -y curl")
                    sh("mkdir -p ${PATCH_NAME}")
                    for(i = 0; i < reposYaml.size(); i++){
                        repo = repos[i]
                        repoName = reposYaml.keySet()[i]

                        sh("mkdir -p ${PATCH_NAME}/${repoName}")
                        sh("echo 'deb ${repo.url} ${repo.distribution} ${repo.component}' > /etc/apt/sources.list")
                        if(repo.keyurl != null){
                            sh("curl -L ${repo.keyurl} | apt-key add -")
                        }else if(repo.insecure == true){
                            insecure = "--allow-unauthenticated"
                        }
                        sh("apt-get clean")
                        sh("apt-get ${insecure} update")
                        if(repo.packages == "all"){
                            sh("cd ${PATCH_NAME}/${repoName};curl -s -H 'User-Agent: Debian APT-HTTP/1.3' ${repo.url}/dists/${repo.distribution}/${repo.component}/binary-amd64/Packages | grep Package: | uniq | sed 's/^\\(Package: \\)*//' | grep -E '*' | xargs -n 1 apt-get -y ${insecure} download")
                        }else{
                            for(pkg in repo.packages){
                                sh("cd ${PATCH_NAME}/${repoName};apt-get -y ${insecure} download ${pkg}")
                            }
                        }
                    }
                }
                stage("Upload patch"){
                    sh("tar -czvf ${PATCH_NAME}.tar.gz -C ${PATCH_NAME} .")
                    sh("curl ${SCRIPT_URL} --output aptly-add-packages.sh")
                    sh("cat aptly-add-packages.sh ${PATCH_NAME}.tar.gz > ${PATCH_NAME}.sh")
                    sh("curl -f -T ${PATCH_NAME}.sh ${UPLOAD_URL}")
                    sh("rm -rf ./*")
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }finally{
            stage("Cleanup"){
                img.inside("-u root:root --hostname=apt-mirror --ulimit nofile=4096:8192") {
                    sh("rm -rf ./*")
                }
            }
        }
    }
}