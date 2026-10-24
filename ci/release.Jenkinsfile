/**
 * Release pipeline for the avec-moi IntelliJ plugin.
 *
 * Strategy (mirrored from the systemdUnitFilePlugin release pipeline):
 *   1. Build (and push, only if the content hash changed) a "build-environment" Docker image that
 *      has the Gradle dependency cache + IntelliJ Platform SDK pre-warmed.
 *   2. Inside a pod running that image, build + test the plugin, tag the release on a release
 *      branch, publish it to the JetBrains Marketplace, and archive the artifacts.
 *
 * avec-moi has no systemd metadata pipeline, so the ubuntu-units / systemd-builder image builds
 * and the metadata-assembly stage are intentionally absent.
 */

def buildDate = new Date().format("yyMMdd", TimeZone.getTimeZone('UTC'))

cron_string = ""

// Build nightly on a release branch
if (BRANCH_NAME ==~ /^([0-9][0-9][0-9]\.x)$/) {
  cron_string = "H 9 * * *"
}

isReleaseDay = new Date().format("E", TimeZone.getTimeZone('UTC')) == "Wed"

def started_by_timer = currentBuild.getBuildCauses()[0]["shortDescription"].matches("Started by timer")
echo "Started by timer: ${started_by_timer}"


if (isReleaseDay && started_by_timer) {
  releaseChannel = "default"
} else {
  releaseChannel = "dev"
}

echo "Release channel $releaseChannel"

def buildPodDefinition(workerPodImage, ciUtilsEnabled, kanikoEnabled) {
  // Pods that run Gradle (worker-pod) or kaniko are "heavy": at most one of them may be scheduled per node,
  // across all builds, so a burst of builds queues instead of piling onto (and thrashing) the fast nodes.
  // The label is shared with the systemdUnitFilePlugin pipeline, so the cap holds across both projects.
  def heavy = workerPodImage != null || kanikoEnabled

  // language=yaml
  yaml = """
kind: Pod
"""

  if (heavy) {
    yaml += """
metadata:
  labels:
    systemd-plugin-ci/load: heavy
"""
  }

  yaml += """
spec:
  affinity:
      nodeAffinity:
        preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 1
            preference:
              matchExpressions:
                - key: perf
                  operator: In
                  values:
                    - fast
"""

  if (heavy) {
    yaml += """
      podAntiAffinity:
        requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchLabels:
                systemd-plugin-ci/load: heavy
            topologyKey: kubernetes.io/hostname
"""
  }

  yaml += """
  hostAliases:
  # Custom set env var
  - ip: "${env.JENKINS_IP_ADDRESS}"
    hostnames:
    # Custom set env var
    - "${env.JENKINS_HOSTNAME}"
  volumes:
  - name: github-ssh-host-key
    configMap:
      name: github-ssh-host-key
  - name: jenkins-docker-cfg
    projected:
      sources:
      - secret:
          name: regcred
          items:
            - key: .dockerconfigjson
              path: config.json
  containers:
"""

  if (kanikoEnabled) {
    yaml += """
  - name: kaniko
    image: ghcr.io/osscontainertools/kaniko:v1.28.5-debug
    imagePullPolicy: Always
    command:
    - sleep
    args:
    - 99d
    resources:
      requests:
        memory: "3172Mi"
    volumeMounts:
    - name: jenkins-docker-cfg
      mountPath: /kaniko/.docker
    - name: github-ssh-host-key
      mountPath:  /github-ssh-host-key
    securityContext:
     # Tried adding a new user but it didn't work maybe I need to look a bit more into it (or set USER and GROUP)
     runAsUser: 0
"""
  }

  if (workerPodImage != null) {
    yaml += """
  - name: worker-pod
    image: "$workerPodImage"
    imagePullPolicy: Always
    command:
    - sleep
    args:
    - 99d
    # Without a limit every JVM sizes its default heap from the node's RAM. Under a limit the JVMs size from
    # the container; the systemdUnitFilePlugin build measured 2.2-3.1Gi RSS this way. The request fits the
    # 8 GB nodes (with jnlp); the limit is kept roomy because it also sets the JVMs' default max heap. A
    # runaway build is OOM-killed in its own cgroup, not the node (SJrX/systemdUnitFilePlugin#559).
    resources:
      requests:
        memory: "4Gi"
      limits:
        memory: "6Gi"
    volumeMounts:
    - name: github-ssh-host-key
      mountPath:  /github-ssh-host-key
    securityContext:
      runAsUser: 1000 # default UID of jenkins user in agent image
"""
  }

  if (ciUtilsEnabled) {
    yaml += """
  - name: ci-utils
    image: ${env.DOCKER_REGISTRY_PREFIX}/ci-utils:latest
    imagePullPolicy: Always
    command:
    - sleep
    args:
    - 99d
    resources:
      requests:
        memory: "128Mi"
      limits:
        memory: "128Mi"
    volumeMounts:
    - name: jenkins-docker-cfg
      mountPath: /kaniko/.docker
    - name: github-ssh-host-key
      mountPath:  /github-ssh-host-key
    securityContext:
      runAsUser: 1000 # default UID of jenkins user in agent image
"""
  }

  return yaml
}

pipeline {
  options {
    // This is important because we are checking out git code at the same time and pushing it back up.
    disableConcurrentBuilds()
    timestamps()
    quietPeriod(1)
    //skipDefaultCheckout()
  }
  triggers {
    cron(cron_string)
  }
  agent none

  stages {
    stage('Create Build Docker Image(s)') {
      stages {
        stage('Determine Image Hashes') {
          agent {
            kubernetes {
              //cloud 'kubernetes'
              defaultContainer 'ci-utils'

              // language=yaml
              yaml buildPodDefinition(null, true, false)
              //workspaceVolume hostPathWorkspaceVolume('/opt/jenkins/workspace')
            }
          }
          steps {
            script {
              buildEnvironmentHash = sh(script: '''
                  sha256sum ./ci/Build-Environment.Dockerfile | sha256sum | sed -E "s/\\s.+$//g"
            ''', returnStdout: true
              ).trim()
              withEnv(["IMAGE=sjrx/avec-moi-build-environment", "TAG=${buildEnvironmentHash}"]) {
                buildEnvironmentBuildNeeded = sh(script: '''
                /check-if-image-exists
                ''', returnStatus: true)
              }
            }
          }
        }
        stage('Build Needed Images') {
          stages {
            stage('Conditionally Build and Push Build Environment') {
              when {
                anyOf {
                  equals expected: 1, actual: buildEnvironmentBuildNeeded
                }
              }
              stages {
                stage("Build and Push Build Environment") {

                  agent {
                    kubernetes {
                      //cloud 'kubernetes'
                      defaultContainer 'kaniko'

                      // language=yaml
                      yaml buildPodDefinition(null, false, true)
                      //workspaceVolume hostPathWorkspaceVolume('/opt/jenkins/workspace')
                    }
                  }

                  steps {
                    container('kaniko') {
                      sh """
                      /kaniko/executor --force -f `pwd`/ci/Build-Environment.Dockerfile -c `pwd`/ci/ --cache=false --compressed-caching=false --destination=${env.DOCKER_REGISTRY_PREFIX}/avec-moi-build-environment:$buildEnvironmentHash
                    """
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    stage("Build & Publish") {
      agent {
        kubernetes {
          //cloud 'kubernetes'
          defaultContainer 'worker-pod'

          // language=yaml
          yaml buildPodDefinition("${env.DOCKER_REGISTRY_PREFIX}/avec-moi-build-environment:$buildEnvironmentHash", false,false)
          //workspaceVolume hostPathWorkspaceVolume('/opt/jenkins/workspace')
        }
      }
      steps {
        withCredentials(
          [
            sshUserPrivateKey(credentialsId: 'ci-ssh-key', keyFileVariable: 'KEYFILE'),
            string(credentialsId: 'avec-moi-publish-token', variable: 'PUBLISH_TOKEN')
          ]
        ) {
          withEnv(["RELEASE_CHANNEL=${releaseChannel}"]) {
            sh("""
              mkdir -p ./build
              ./generate-changelog  > build/CHANGELOG
              ./gradlew --no-daemon -I ./build-cache-init.gradle.kts -I ./repo-cache-init.gradle.kts --build-cache build buildPlugin -PbuildScan
              """)
            script {
              if (env.BRANCH_NAME ==~ /^([0-9][0-9][0-9]\.x)$/) {
                sh("""
                echo "Tagging"
                mkdir -p ~/.ssh/

                VERSION=\$(ls -1 ./build/distributions/*.zip | sort | tail -n 1 | sed -E "s/.+-(.+).zip\$/\\1/g")
                cp /github-ssh-host-key/* ~/.ssh/
                export GIT_SSH_COMMAND="ssh -i \$KEYFILE"
                git config --global user.email "jenkins@sjrx.net"
                git config --global user.name "Jenkins CI System"


                echo "Current Version \$VERSION"

                git remote add origin-ssh git@github.com:SJrX/avec-moi.git
                git tag "v\${VERSION}"
                git push origin-ssh --tags
"""
                )
                sh("""
                ./gradlew --no-daemon -I ./build-cache-init.gradle.kts --build-cache publishPluginStandalone -PbuildScan
""")
              }
              else {
                sh("""
                echo "No tagging"
""")
              }

            }

            archiveArtifacts artifacts: 'build/distributions/*.zip'
            archiveArtifacts artifacts: 'build/reports/**'
          }
        }
      }
    }
  }
  post {
    failure {
      mail body: "<br>Project: ${env.JOB_NAME} <br>Build: <a href=\"${env.BUILD_URL}\">#${env.BUILD_NUMBER}</a> Failed", charset: 'UTF-8', mimeType: 'text/html',
           subject: "ERROR CI: Project name -> ${env.JOB_NAME}", to: "${env.FAILED_BUILD_NOTIFICATION_EMAIL}"

    }
    fixed {
      mail body: "<b>Example</b><br>Project: ${env.JOB_NAME} <br>Build: <a href=\"${env.BUILD_URL}\">${env.BUILD_NUMBER}</a> Fixed\"", charset: 'UTF-8', mimeType: 'text/html',
           subject: "OK CI: Project name -> ${env.JOB_NAME}", to: "${env.FAILED_BUILD_NOTIFICATION_EMAIL}"
    }
  }
}
