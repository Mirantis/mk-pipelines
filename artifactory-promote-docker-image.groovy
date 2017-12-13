#!groovy

/**
 *
 * Promote docker image from one artifactory repository (development) to
 * another (production)
 *
 * Expected parameters:
 *   REPO_SRC          Source Artifactory repository (default 'docker-dev-local')
 *   REPO_DST          Destination Artifactory repository (default 'docker-prod-local')
 *   IMAGE_SRC         Source image name (without docker registry!) to promote (required)
 *   IMAGE_DST         Destination image (default same as IMAGE_SRC)
 *
 *   COPY_IMAGE        Copy image instead of moving (default 'true')
 *
 *   ARTIFACTORY_URL   Base URL of Artifactory instance, i.e. without `/api/...` path.
 *                       (default 'https://artifactory.mcp.mirantis.net/artifactory/')
 *   ARTIFACTORY_CREDS Credentials to login into Artifactory (default 'artifactory')
 *
 *   SLAVE_LABEL       Label of the slave to run job (default 'master')
 *
 *   Slave requirements: curl installed
 *
 */

import groovy.json.JsonOutput

String repo_src = env.REPO_SRC ?: 'docker-dev-local'
String repo_dst = env.REPO_DST ?: 'docker-prod-local'
String image_src = env.IMAGE_SRC
String image_dst = env.IMAGE_DST ?: env.IMAGE_SRC

boolean copy_image = env.COPY_IMAGE.asBoolean() ?: true

String artifactory_url = env.ARTIFACTORY_URL ?: 'https://artifactory.mcp.mirantis.net/artifactory/'
String artifactory_creds = env.ARTIFACTORY_CREDS ?: 'artifactory'

String slave_label = env.SLAVE_LABEL ?: 'master'

// Delimiter for splitting docker image name and tag (to avoid codeNarc DRY warning)
String _colon = ':'

String img_src_name, img_src_tag
String img_dst_name, img_dst_tag

node(slave_label) {
    (img_src_name, img_src_tag) = image_src.tokenize(_colon)
    (img_dst_name, img_dst_tag) = image_dst.tokenize(_colon)

    String api_req = JsonOutput.toJson([
        targetRepo: repo_dst,
        dockerRepository: img_src_name,
        targetDockerRepository: img_dst_name,
        tag: img_src_tag,
        targetTag: img_dst_tag,
        copy: copy_image,
    ])

    withCredentials([usernameColonPassword(credentialsId: artifactory_creds, variable: 'USERPASS')]) {
        sh """
            curl -fLsS \
                -u \$USERPASS \
                -X POST -d '${api_req}' -H 'Content-Type: application/json' \
                '${artifactory_url}api/docker/${repo_src}/v2/promote'
        """
    }
}
