def generate_add_user_script() {
    stage('generate_add_user_script') {
        script {
          sh '''#!/bin/sh
              my_UID=$(id -u)
              my_GID=$(id -g)
              my_NAME=$(whoami)
              cat <<EOF > generate_add_user_script.sh
              #!/bin/sh
              if [ -f "/etc/alpine-release" ]; then
              	addgroup -g $my_GID $my_NAME
              	adduser -u $my_UID -g $my_GID -D -S $my_NAME
              else
              	groupadd -g $my_GID $my_NAME
              	useradd -u $my_UID -g $my_GID $my_NAME
              fi

              mkdir -p /home/$my_NAME >/dev/null 2>&1
              chown -R $my_NAME:$my_GID /home/$my_NAME
          '''
          sh 'chmod +x generate_add_user_script.sh'
        }//script
    }//stage
}

def generate_aws_environment() {
    stage('generate_aws_environment') {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: "${PROFILE}", secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
            withCredentials([string(credentialsId: 'GITHUB_TOKEN', variable: 'GITHUB_TOKEN')]) {
                try {
                    //Trying to parse test if ANSIBLE_VAULT_ID is defined by
                    //two means - from groovy variable scope or from env. which
                    //is generated by Jenkins while parsing the build
                    //parameters. Thus the caller can have two way to supply
                    //this var or not supply at all - then we wont generate the
                    //ansible vault related code.
                    ANSIBLE_VAULT_ID  = env.ANSIBLE_VAULT_ID ?: ANSIBLE_VAULT_ID
                    withCredentials([string(credentialsId: "${ANSIBLE_VAULT_ID}", variable: 'VAULT')]) {
                        //As we do not run code within this block, we pick up
                        //the value and push it to env value to be
                        //used in the next script shell generation
                        env.VAULT = VAULT
                    }//withCred
                }
                catch(Exception ex) {
                    println("No ANSIBLE_VAULT_ID given")
                    env.VAULT = ''
                }
            sh '''cat <<EOF > generate_aws_environment.sh
#!/bin/bash -e
mkdir -p ~/.aws

printf "[$PROFILE]
output=json
region=ap-southeast-2" > ~/.aws/config

printf "[$PROFILE]
aws_access_key_id = ${AWS_ACCESS_KEY_ID}
aws_secret_access_key = ${AWS_SECRET_ACCESS_KEY}" > ~/.aws/credentials

if [ "${VAULT}" != "" ]; then
    VAULT_FILE=\\$(grep -Po '(?<=vault_password_file = )[^\\s]+' ansible.cfg | sed 's/~\\///')
    echo "Vault file path: ~/\\${VAULT_FILE}"
    mkdir -p \\$(dirname ~/\\${VAULT_FILE})
    echo "${VAULT}" > ~/\\${VAULT_FILE}
    chmod 0600 ~/\\${VAULT_FILE}
    echo "Vault file: "
    ls -lha ~/\\${VAULT_FILE}
    sed -i "s|git+ssh://git|https://${GITHUB_TOKEN}|g" requirements.yml
    ./ansible-common/update-galaxy.py
fi
EOF
'''
          sh 'chmod +x generate_aws_environment.sh'
        }//withCred github
      }//withCred AWS
    }//stage
}

def run_build_script(arg1=[:]) {
    def default_arg = ['docker_net_opt': '--net=container:xvt', 'docker_volume_opt': '--volumes-from xvt_jenkins', 'docker_image': 'xvtsolutions/python3-aws-ansible:2.7.9']
    def arg = default_arg + arg1
    stage('run_build_script') {
        script {
            docker.image(arg.docker_image).withRun("-u root ${arg.docker_volume_opt} ${arg.docker_net_opt}") { c->
                if (fileExists('generate_add_user_script.sh')) {
                    sh "docker exec --workdir ${WORKSPACE} ${c.id} bash ./generate_add_user_script.sh"
                }
                else {
                    echo 'generate_add_user_script.sh does not exist - skipping'
                }
                if (fileExists('generate_aws_environment.sh')) {
                    sh "docker exec --user jenkins --workdir ${WORKSPACE} ${c.id} ./generate_aws_environment.sh"
                }
                else {
                    echo 'generate_aws_environment.sh does not exist - skipping'
                }
                if (fileExists('build.sh')) {
                    sh "docker exec --user jenkins --workdir ${WORKSPACE} ${c.id} ./build.sh"
                }
                else {
                    echo 'build.sh does not exist - skipping'
                }
                sh 'rm -rf build.sh add-user.sh ~/.aws ~/.ansible ubuntu || true'
            }//docker env
        }//script
    }//stage
}

def remove_file(file_name) {
    if (isUnix()) {
        sh "rm -f ${file_name} || true"
    }
    else {
        powershell """Remove-Item -Path '${file_name}'
        exit 0
        """
    }
}

def save_build_data(build_data=[:]) {
    stage('save_build_data') {
        script {
            def default_data = [
                build_number: "${BUILD_NUMBER}",
                branch_name: "${BRANCH_NAME}",
                git_revision: "${GIT_REVISION}",
                upstream_build_url: "${BUILD_URL}",
                upstream_job_name: "${JOB_NAME}",
                upstream_job_base_name: "${JOB_BASE_NAME}",
                artifact_version: "${BUILD_VERSION}"
                ]
            def data = default_data + build_data
            remove_file('artifact_data.yml')
            writeYaml file: 'artifact_data.yml', data: data
            archiveArtifacts allowEmptyArchive: true, artifacts: 'artifact_data.yml', fingerprint: true, onlyIfSuccessful: true
        } //script
    }// Gather artifacts
}

def load_upstream_build_data() {
    stage('load_upstream_build_data') {
        script {
            if (env.UPSTREAM_BUILD_NUMBER == 'LAST_SAVED_BUILD') {
              copyArtifacts filter: 'artifact_data.yml', fingerprintArtifacts: true, flatten: true, projectName: "${UPSTREAM_JOB_NAME}", selector: latestSavedBuild()
            }
            else if (env.UPSTREAM_BUILD_NUMBER == 'LAST_SUCCESS_BUILD')  {
                copyArtifacts filter: 'artifact_data.yml', fingerprintArtifacts: true, flatten: true, projectName: "${UPSTREAM_JOB_NAME}", selector: lastSuccessful()
            }
            else {
              copyArtifacts filter: 'artifact_data.yml', fingerprintArtifacts: true, flatten: true, projectName: "${UPSTREAM_JOB_NAME}", selector: specific("${UPSTREAM_BUILD_NUMBER}")
            }//If
            // Parsing artifact data
            ARTIFACT_DATA = readYaml(file: 'artifact_data.yml')
            env.ARTIFACT_FILENAME = ARTIFACT_DATA.artifact_filename ?: null
            env.UPSTREAM_REVISION = ARTIFACT_DATA.git_revision ?: null
            env.ARTIFACT_REVISION = ARTIFACT_DATA.artifact_revision ?: (ARTIFACT_DATA.git_revision ?: null)
            env.ARTIFACT_VERSION = ARTIFACT_DATA.artifact_version ?: null
            env.UPSTREAM_BUILD_NUMBER = ARTIFACT_DATA.build_number ?: null
            env.UPSTREAM_BRANCH_NAME = ARTIFACT_DATA.branch_name ?: null
            env.UPSTREAM_BUILD_URL = ARTIFACT_DATA.upstream_build_url ?: null
            env.UPSTREAM_JOB_NAME = ARTIFACT_DATA.upstream_job_name ?: null
        }//script
    }//stage
}


def is_sub_map(m0, m1, regex_match=[:]) {
//Test if a map m0 a sub map of m1. sub map is defined that for all keys in m0
//m1 must have that key and m1 must have the value that is equal of m0 pair.
//The value can be match using regex per key - by default is exact match.
    def filter_keys = m0.keySet()
    def output = true
    for (filter_key in filter_keys) {
        if (! regex_match[filter_key]) {
            if (! (m1.containsKey(filter_key) && m1[filter_key] == m0[filter_key]) ) {
                output = false
                break
            }
        }
        else {
            def m0_val = m0[filter_key]
            def m1_val = m1[filter_key]
            if (! (m1.containsKey(filter_key) && m1_val.matches(".*${m0_val}.*")))  {
                output = false
                break
            }
        }
    }
    return output
}


def get_build_param_by_name(job_name, param_filter=[:], regex_match=[:]) {
//Get the param of the last success build of a job_name matching the
//param_filter map.
    stage('get_build_by_name_env') {
        script {
            def output = [:]
            def selected_build = null

            Jenkins.instance.getAllItems(Job).findAll() {job -> job.name == job_name}.each{
                def selected_param_kv = [:]
                def jobBuilds = it.getBuilds()
                for (i=0; i < jobBuilds.size(); i++) {
                    def current_job = jobBuilds[i]

                    if (! current_job.getResult().toString().equals("SUCCESS")) continue

                    def current_parameters = current_job.getAction(ParametersAction)?.parameters

                    def current_param_kv = [:]
                    current_parameters.each { param ->
                        current_param_kv[param.name] = param.value
                    }

                    if (is_sub_map(param_filter, current_param_kv, regex_match)) {
                        //Merge values in description to param
                        def job_description_lines = current_job.getDescription().split('<br/>')
                        def job_description_map = [:]
                        job_description_lines.each { line ->
                        	def _kvlist = line.split(/\:[\s]+/)
                            if (_kvlist.size() == 2) {
                                job_description_map[_kvlist[0].replaceAll(/^[\s]*/,'').replaceAll(/[\s]*$/,'') ] = _kvlist[1].replaceAll(/^[\s]*/,'').replaceAll(/[\s]*$/,'')
                            }
                        }
			            output = current_param_kv + job_description_map
                        break
                    }
                }
            }// each job
            return output
        }//script
    }//stage
}


return this
