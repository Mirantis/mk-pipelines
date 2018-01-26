#!groovy

// Collect parameters
String mirror_name   = env.MIRROR_NAME
String mirror_target = env.MIRROR_TARGET ?: env.MIRROR_NAME

String snapshot_name = env.SNAPSHOT_NAME as String
String snapshot_id   = env.SNAPSHOT_ID   as String
String snapshot_dir  = env.SNAPSHOT_DIR
String snapshot_rel_dir  = env.SNAPSHOT_REL_DIR

String root_dir      = env.ROOT_DIR

String slave_label   = env.SLAVE_LABEL

// Snapshot name can be hierarchical, i.e. can have subdirectories, so let's flatten it
String normalized_snapshot_name = snapshot_name.replaceAll('/', '-')

String _snapshot = ''

node(slave_label) {
    try {
        dir(snapshot_dir) {
            // Guess link target
            if (snapshot_id ==~ /^\d{4}-\d{2}-\d{2}-\d{6}$/) {
                // Exact snapshot ID
                _snapshot = "${mirror_target}-${snapshot_id}"
            } else if (snapshot_id == 'latest') {
                // Latest available snapshot
                _snapshot = sh (script: "sed '1p;d' '${mirror_target}-${snapshot_id}.target.txt'", returnStdout: true).trim()
            } else {
                // Some named snapshot
                _snapshot = sh (script: "readlink '${mirror_target}-${snapshot_id}'", returnStdout: true).trim()
            }

            // Set name for the snapshot to prevent it from time-based cleanup
            sh "ln -sfn '${_snapshot}' '${mirror_target}-${normalized_snapshot_name}'"
        }

        // Set top-level name
        dir("${root_dir}/${snapshot_name}") {
            sh "ln -sfn '${snapshot_rel_dir}/${_snapshot}' '${mirror_name}'"
            sh "echo '${snapshot_rel_dir}/${_snapshot}' > '${mirror_name}'.target.txt"
        }
    } finally {
        // Cleanup
        dir("${snapshot_dir}@tmp") {
            deleteDir()
        }
        dir("${root_dir}/${snapshot_name}@tmp") {
            deleteDir()
        }
    }
}

// Set build description
currentBuild.description = "<p><b>${_snapshot}</b> (from ${snapshot_id})</p>"

