def call(String name = 'human') {
    echo "Hello, ${name}."
}

String repoName() {
    return scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0]
}
