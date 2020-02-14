String repoName() {
    return scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0]
}

return this
