/**
 * Release pipeline for the avec-moi IntelliJ plugin.
 *
 * Strategy (mirrored from the systemdUnitFilePlugin release pipeline):
 *   1. Build (and push, only if the content hash changed) a "build-environment" Docker image that
 *      has the Gradle dependency cache + IntelliJ Platform SDK pre-warmed.
 *   2. Inside a pod running that image, build + test the plugin.
 *
 * On a release branch (NNN.x), if anything releasable landed since the last stable tag:
 *   3. Publish a "<version>-dev" build to the Marketplace dev channel straight away.
 *   4. Wait at a release gate (no pod held). "Publish now" releases immediately, "Abort" skips this
 *      release, and if nobody answers within RELEASE_GATE_HOURS it publishes by default.
 *   5. Publish <version> to the stable channel, then tag it v<version>.
 *
 * Each merge gets its own gate; once a newer build passes its gate, older builds still waiting are
 * aborted (milestone), so a burst of merges ships once, newest first, never out of order.
 *
 * Unlike the systemd plugin there is no nightly build: avec-moi has no upstream input that changes
 * without a commit, so merges are the only trigger. There is also no systemd metadata pipeline, so
 * the ubuntu-units / systemd-builder image builds and the metadata-assembly stage are absent.
 */

isReleaseBranch = env.BRANCH_NAME ==~ /^([0-9][0-9][0-9]\.x)$/

RELEASE_GATE_HOURS = 8

// Set during "Build & Test" on a release branch: whether anything worth shipping landed since the
// last stable tag, and the version the build produced.
releasable = false
pluginVersion = null

// Trusts GitHub's host key and points git at the CI deploy key (KEYFILE, from withCredentials).
GIT_SSH_SETUP = '''
  mkdir -p ~/.ssh/
  cp /github-ssh-host-key/* ~/.ssh/
  export GIT_SSH_COMMAND="ssh -i $KEYFILE"
  git config --global user.email "jenkins@sjrx.net"
  git config --global user.name "Jenkins CI System"
'''

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
    // Builds may overlap: a build waiting at the release gate must not hold up the next merge. The
    // milestone after the gate keeps releases in order instead, and only unique v<version> tags are pushed.
    timestamps()
    quietPeriod(1)
    //skipDefaultCheckout()
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
    stage("Build & Test") {
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
          sh("""
            mkdir -p ./build
            ./generate-changelog  > build/CHANGELOG
            ./gradlew --no-daemon -I ./build-cache-init.gradle.kts -I ./repo-cache-init.gradle.kts --build-cache build buildPlugin -PbuildScan
            """)
          script {
            // The zip is build/distributions/avec-moi-<version>.zip; <version> itself may contain dashes.
            pluginVersion = sh(script: '''
              basename "$(ls -1 ./build/distributions/avec-moi-*.zip | sort | tail -n 1)" .zip | sed -e 's/^avec-moi-//'
              ''', returnStdout: true).trim()
            if (!pluginVersion) {
              error("No plugin zip in build/distributions")
            }
            echo "Plugin version ${pluginVersion}"

            if (isReleaseBranch) {
              // Releasable = any commit since the last stable tag that isn't ci/chore/docs/style/test
              // housekeeping (or the release-branch automation's gradle.properties commit). With no
              // stable tag yet, everything is releasable.
              releasable = sh(script: GIT_SSH_SETUP + '''
                git fetch --tags --force git@github.com:SJrX/avec-moi.git
                last=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null) || { echo "No stable tag yet"; exit 0; }
                echo "Last stable tag: $last"
                git log --no-merges --format=%s "$last"..HEAD \\
                  | grep -v -E '^(ci|chore|docs|style|test)(\\(.+\\))?!?:' \\
                  | grep -v '^Update gradle.properties' \\
                  | grep .
                ''', returnStatus: true) == 0
              echo(releasable ? "Releasable changes since the last stable tag" : "Nothing to release since the last stable tag")
            }

            if (releasable) {
              // Same build, rebuilt with a -dev suffix: Marketplace versions must be unique, and the
              // unsuffixed version is reserved for the stable upload after the gate.
              withEnv(["PLUGIN_VERSION=${pluginVersion}-dev", "RELEASE_CHANNEL=dev"]) {
                sh("""
                  ./gradlew --no-daemon -I ./build-cache-init.gradle.kts -I ./repo-cache-init.gradle.kts --build-cache buildPlugin -PbuildScan
                  ./gradlew --no-daemon -I ./build-cache-init.gradle.kts --build-cache publishPluginStandalone -PbuildScan
                  """)
              }
              stash includes: "build/distributions/*-${pluginVersion}.zip", name: 'stable-plugin-zip'
            }
          }

          archiveArtifacts artifacts: 'build/distributions/*.zip'
          archiveArtifacts artifacts: 'build/reports/**'
        }
      }
    }
    stage("Release Gate") {
      // No agent: a build waiting here holds no pod or executor.
      when {
        expression { releasable }
      }
      steps {
        script {
          try {
            timeout(time: RELEASE_GATE_HOURS, unit: 'HOURS') {
              input message: "Publish ${pluginVersion} to the stable channel? It publishes automatically after ${RELEASE_GATE_HOURS} hours; Abort skips this release.",
                    ok: 'Publish now'
            }
          } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            // Abort clicked, or superseded by a newer build: rethrow so nothing is published. Only a
            // timeout means "nobody objected", so carry on.
            if (!e.causes.any { it instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout }) {
              throw e
            }
            echo "No answer within ${RELEASE_GATE_HOURS} hours; publishing by default"
          }
        }
        // Passing this aborts every older build that hasn't, so an older release can't ship after a newer one.
        milestone(ordinal: 100, label: 'release approved')
      }
    }
    stage("Publish") {
      when {
        beforeAgent true
        expression { releasable }
      }
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
          unstash 'stable-plugin-zip'
          // PLUGIN_VERSION pins the version, so a gate that resolves after midnight still finds the zip.
          withEnv(["PLUGIN_VERSION=${pluginVersion}", "RELEASE_CHANNEL=default"]) {
            sh("""
              ./gradlew --no-daemon -I ./build-cache-init.gradle.kts --build-cache publishPluginStandalone -PbuildScan
              """)
          }
          // Tag only once it's actually on the Marketplace.
          sh(GIT_SSH_SETUP + """
            git tag "v${pluginVersion}"
            git push git@github.com:SJrX/avec-moi.git "v${pluginVersion}"
            """)
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
