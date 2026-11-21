import jenkins.model.Jenkins
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.plugins.git.GitSCM
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.git.extensions.impl.PathRestriction
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import jenkins.model.JenkinsLocationConfiguration

def instance = Jenkins.get()
def marker = new File(instance.rootDir, 'zgridflow-initialized')
if (!marker.exists()) {
    def password = new File(instance.rootDir, 'admin-password').text.trim()
    def realm = new HudsonPrivateSecurityRealm(false)
    realm.createAccount('admin', password)
    instance.setSecurityRealm(realm)
    def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
    strategy.setAllowAnonymousRead(false)
    instance.setAuthorizationStrategy(strategy)
    // One trusted local job; this POC intentionally uses the Mac as its build agent.
    instance.setNumExecutors(1)
    JenkinsLocationConfiguration.get().setUrl('http://localhost:18080/')
    instance.save()
    marker.text = 'Local POC initialized\n'
}

def job = instance.getItem('zgridflow-poc')
if (job == null) {
    job = instance.createProject(WorkflowJob, 'zgridflow-poc')
    def scm = new GitSCM(
        [new UserRemoteConfig('https://github.com/scala-szeged/zgridflow.git', null, null, null)],
        [new BranchSpec('*/main')], false, [], null, null,
        [new PathRestriction('', 'apps/local/poc-values\\.yaml')]
    )
    job.setDefinition(new CpsScmFlowDefinition(scm, 'Jenkinsfile'))
    job.setDescription('Local Scala/ZIO + TypeScript POC: test, build, load kind images, commit Git desired state, verify Argo CD.')
    job.save()
}
