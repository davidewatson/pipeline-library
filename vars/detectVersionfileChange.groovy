def call(Map defaultVals) {
  getString(currentBuild, defaultVals)
  return isVersionFileChanged(currentBuild, defaultVals)
}

@NonCPS
def isVersionFileChanged(thisBuild, defaults) {
  def changeLogSets = thisBuild.changeSets
  
  for (changeLogSet in changeLogSets) {
    for (entry in changeLogSet.items) {      
      def files = new ArrayList(entry.affectedFiles)
      for (changedFile in files) {
        if (changedFile.path.endsWith("/${defaults.versionfile}")) {
          return true
        }
      }
    }
  }

  return false
}

def getString(thisBuild, defaults) {
  def changeLogSets = currentBuild.rawBuild.changeSets
  for (int i = 0; i < changeLogSets.size(); i++) {
      def entries = changeLogSets[i].items
      for (int j = 0; j < entries.length; j++) {
          def entry = entries[j]
          echo "${entry.commitId} by ${entry.author} on ${new Date(entry.timestamp)}: ${entry.msg}"
          def files = new ArrayList(entry.affectedFiles)
          for (int k = 0; k < files.size(); k++) {
              def file = files[k]
              echo "  ${file.editType.name} ${file.path}"
          }
      }
  }
}