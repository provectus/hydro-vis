properties([
  parameters([
    choice(choices: ['patch','minor','major'], name: 'patchVersion', description: 'What needs to be bump?'),
    string(defaultValue:'', description: 'Force set newVersion or leave empty', name: 'newVersion', trim: false),
    string(defaultValue:'', description: 'Set grpcVersion or leave empty', name: 'grpcVersion', trim: false),
    string(defaultValue:'', description: 'Set sdkVersion or leave empty', name: 'sdkVersion', trim: false),
    choice(choices: ['local', 'global'], name: 'releaseType', description: 'It\'s local release or global?'),
   ])
])

SERVICENAME = 'hydro-visualization'
SEARCHPATH  = './requirements.txt'
SEARCHSDK   = 'hydrosdk'
SEARCHGRPC  = 'hydro-serving-grpc'
REGISTRYURL = 'hydrosphere'
GITHUBREPO  = "github.com/Hydrospheredata/hydro-visualization.git"

def checkoutRepo(String repo){
  if (env.CHANGE_ID != null ){
    git changelog: false, credentialsId: 'HydroRobot_AccessToken', poll: false, url: repo, branch: env.CHANGE_BRANCH
  } else {
    git changelog: false, credentialsId: 'HydroRobot_AccessToken', poll: false, url: repo, branch: env.BRANCH_NAME
  }
}

def getVersion(){
    try{
      if (params.releaseType == 'global'){
        //remove only quotes
        version = sh(script: "cat \"version\" | sed 's/\\\"/\\\\\"/g'", returnStdout: true ,label: "get version").trim()
      } else {
        //Set version as commit SHA
        version = sh(script: "git rev-parse HEAD", returnStdout: true ,label: "get version").trim()
      }
        return version
    }catch(err){
        return "$err" 
    }
}

def slackMessage(){
    withCredentials([string(credentialsId: 'slack_message_url', variable: 'slack_url')]) {
    //beautiful block
      def json = """
{
	"blocks": [
		{
			"type": "header",
			"text": {
				"type": "plain_text",
				"text": "$SERVICENAME: release - ${currentBuild.currentResult}!",
				"emoji": true
			}
		},
		{
			"type": "section",
			"block_id": "section567",
			"text": {
				"type": "mrkdwn",
				"text": "Build info:\n    Project: $JOB_NAME\n    Author: $AUTHOR\n    SHA: $newVersion"
			},
			"accessory": {
				"type": "image",
				"image_url": "https://res-5.cloudinary.com/crunchbase-production/image/upload/c_lpad,h_170,w_170,f_auto,b_white,q_auto:eco/oxpejnx8k2ixo0bhfsbo",
				"alt_text": "Hydrospere loves you!"
			}
		},
		{
			"type": "section",
			"text": {
				"type": "mrkdwn",
				"text": "You can see the assembly details by clicking on the button"
			},
			"accessory": {
				"type": "button",
				"text": {
					"type": "plain_text",
					"text": "Details",
					"emoji": true
				},
				"value": "Details",
				"url": "${env.BUILD_URL}",
				"action_id": "button-action"
			}
		}
	]
}
"""
    //Send message
        sh label:"send slack message",script:"curl -X POST \"$slack_url\" -H \"Content-type: application/json\" --data '${json}'"
    }
}

def bumpVersion(String currentVersion,String newVersion, String patch, String path){
    sh script: """cat <<EOF> ${WORKSPACE}/bumpversion.cfg
[bumpversion]
current_version = 0.0.0
commit = False
tag = False
parse = (?P<major>\\d+)\\.(?P<minor>\\d+)\\.(?P<patch>\\d+)
serialize =
    {major}.{minor}.{patch}

EOF""", label: "Set bumpversion configfile"
    if (newVersion != null && newVersion != ''){ //TODO: needs verify valid semver
        sh("echo $newVersion > version") 
    }else{
        sh("bumpversion $patch $path --config-file '${WORKSPACE}/bumpversion.cfg' --allow-dirty --verbose --current-version '$currentVersion'")   
    }
}

def bumpGrpc(String newVersion, String search, String patch, String path){
    sh script: "cat $path | grep '$search' > tmp", label: "Store search value in tmp file"
    currentVersion = sh(script: "cat tmp | cut -d'%' -f4 | sed 's/\"//g' | sed 's/,//g' | sed 's/^.*=//g'", returnStdout: true, label: "Get current version").trim()
    sh script: "sed -i -E \"s/$currentVersion/$newVersion/\" tmp", label: "Bump temp version"
    sh script: "sed -i 's/\\\"/\\\\\"/g' tmp", label: "remove quote and space from version"
    sh script: "sed -i \"s/.*$search.*/\$(cat tmp)/g\" $path", label: "Change version"
    sh script: "rm -rf tmp", label: "Remove temp file"
}

//Create github release
def releaseService(String xVersion, String yVersion){
  withCredentials([usernamePassword(credentialsId: 'HydroRobot_AccessToken', passwordVariable: 'password', usernameVariable: 'username')]) {
      //Set global git
      sh script: "git diff", label: "show diff"
      sh script: "git commit -a -m 'Bump to $yVersion'", label: "commit to git"
      sh script: "git push --set-upstream origin master", label: "push all file to git"
      sh script: "git tag -a $yVersion -m 'Bump $xVersion to $yVersion version'",label: "set git tag"
      sh script: "git push --set-upstream origin master --tags",label: "push tag and create release"
      //Create release from tag
      sh script: "curl -X POST -H \"Accept: application/vnd.github.v3+json\" -H \"Authorization: token ${password}\" https://api.github.com/repos/Hydrospheredata/${SERVICENAME}/releases -d '{\"tag_name\":\"${yVersion}\",\"name\": \"${yVersion}\",\"body\": \"Bump to ${yVersion}\",\"draft\": false,\"prerelease\": false}'"
  }
}

def buildDocker(){
    //run build command and store build tag 
    tagVersion = getVersion() 
    sh script: "docker build -t hydrosphere/${SERVICENAME}:$tagVersion .", label: "Run build docker task";
    sh script: "docker tag hydrosphere/${SERVICENAME}:$tagVersion hydrosphere/${SERVICENAME}:latest", label: "Run build docker task";
}

def pushDocker(String registryUrl, String dockerImage){
    //push docker image to registryUrl
    withCredentials([usernamePassword(credentialsId: 'hydrorobot_docker_creds', passwordVariable: 'password', usernameVariable: 'username')]) {
      sh script: "docker login --username ${username} --password ${password}"
      //sh script: "docker tag hydrosphere/$dockerImage $registryUrl/$dockerImage",label: "set tag to docker image"
      sh script: "docker push $registryUrl/$dockerImage",label: "push docker image to registry"
    }
}

def updateDockerCompose(String newVersion){
  dir('docker-compose'){
    //Change template
    sh script: "sed -i \"s/.*image:.*/    image: hydrosphere\\/${SERVICENAME}:$newVersion/g\" ${SERVICENAME}.service.template", label: "sed $SERVICENAME version"
    //Merge compose into 1 file
    composeMerge = "docker-compose"
    composeService = sh label: "Get all template", returnStdout: true, script: "ls *.template"
    list = composeService.split( "\\r?\\n" )
    for(l in list){
        composeMerge = composeMerge + " -f $l"
    }
    composeMerge = composeMerge + " config > ../docker-compose.yaml"
    sh script: "$composeMerge", label:"Merge compose file"
    //sh script: "cp docker-compose.yaml ../docker-compose.yaml"
  }
}

def updateHelmChart(String newVersion){
  dir('helm'){
    //Change template
    sh script: "sed -i \"s/.*full:.*/  full: hydrosphere\\/${SERVICENAME}:$newVersion/g\" visualization/values.yaml", label: "sed ${SERVICENAME} version"
    sh script: "sed -i \"s/.*hydrosphere\\/hydro-visualization.*/    full: hydrosphere\\/${SERVICENAME}:$newVersion/g\" ../dev.yaml", label: "sed ${SERVICENAME} dev stage version"

    //Refresh readme for chart
    sh script: "frigate gen visualization --no-credits > visualization/README.md"

    //TODO: use atlas
    dir('visualization'){
        sh script: "helm lint .", label: "Lint visualization chart"
        sh script: "helm template -n serving --namespace hydrosphere . > test.yaml", label: "save template to file"
        sh script: "polaris audit --audit-path test.yaml -f yaml", label: "lint template by polaris"
        sh script: "polaris audit --audit-path test.yaml -f score", label: "get polaris score"
        sh script: "rm -rf test.yaml", label: "remove test.yaml"
    }
    //Lint serving and bump version for dev stage
    dir('serving'){
        sh script: "helm dep up", label: "Dependency update"
        sh script: "helm lint .", label: "Lint all charts"
        sh script: "helm template -n serving --namespace hydrosphere . > test.yaml", label: "save template to file"
        sh script: "polaris audit --audit-path test.yaml -f yaml", label: "lint template by polaris"
        sh script: "polaris audit --audit-path test.yaml -f score", label: "get polaris score"
        sh script: "rm -rf test.yaml", label: "remove test.yaml"
    }
  }
}

node('hydrocentral') {
  try{
    stage('SCM'){
      sh script: "git config --global user.name \"HydroRobot\"", label: "Set username"
      sh script: "git config --global user.email \"robot@hydrosphere.io\"", label: "Set user email" 
      checkoutRepo("https://github.com/Hydrospheredata/${SERVICENAME}" + '.git')
      AUTHOR = sh(script:"git log -1 --pretty=format:'%an'", returnStdout: true, label: "get last commit author").trim()
      if (params.grpcVersion == ''){
          //Set grpcVersion
          grpcVersion = sh(script: "curl -Ls https://pypi.org/pypi/hydro-serving-grpc/json | jq -r .info.version", returnStdout: true, label: "get grpc version").trim()
        }
      if (params.sdkVersion == ''){
          //Set sdkVersion
          sdkVersion = sh(script: "curl -Ls https://pypi.org/pypi/hydrosdk/json | jq -r .info.version", returnStdout: true, label: "get sdk version").trim()
        }
      }

    stage('Test'){
      if (env.CHANGE_ID != null){
        buildDocker()
      }
    }

        stage('Release'){
            if (BRANCH_NAME == 'master' || BRANCH_NAME == 'main'){
                if (params.releaseType == 'global'){
                    oldVersion = getVersion()
                    bumpVersion(getVersion(),params.newVersion,params.patchVersion,'version')
                    newVersion = getVersion()
                    bumpGrpc(sdkVersion,SEARCHSDK, params.patchVersion,SEARCHPATH) 
                    bumpGrpc(grpcVersion,SEARCHGRPC, params.patchVersion,SEARCHPATH) 
                } else {
                    newVersion = getVersion()
                }
                buildDocker()
                pushDocker(REGISTRYURL, SERVICENAME+":$newVersion")
                pushDocker(REGISTRYURL, SERVICENAME+":latest")
                //Update helm and docker-compose if release 
                if (params.releaseType == 'global'){
                    releaseService(oldVersion, newVersion)
                } else {
                    dir('release'){
                        //bump only image tag
                        withCredentials([usernamePassword(credentialsId: 'HydroRobot_AccessToken', passwordVariable: 'Githubpassword', usernameVariable: 'Githubusername')]) {
                        git changelog: false, credentialsId: 'HydroRobot_AccessToken', url: "https://$Githubusername:$Githubpassword@github.com/Hydrospheredata/hydro-serving.git"      
                        updateHelmChart("$newVersion")
                        updateDockerCompose("$newVersion")
                        sh script: "git commit --allow-empty -a -m 'Releasing $SERVICENAME:$newVersion'",label: "commit to git chart repo"
                        sh script: "git push https://$Githubusername:$Githubpassword@github.com/Hydrospheredata/hydro-serving.git --set-upstream master",label: "push to git"
                        }
                    }
                }
            }
        }
      //post if success
      if (params.releaseType == 'local' && BRANCH_NAME == 'master'){
          slackMessage()
      }
    } catch (e) {
    //post if failure
      currentBuild.result = 'FAILURE'
      if (params.releaseType == 'local' && BRANCH_NAME == 'master'){
          slackMessage()
      }
        throw e
    }
}