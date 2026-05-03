package io.jenkins.plugins.nomad.pipeline

import org.jenkinsci.plugins.pipeline.modeldefinition.agent.CheckoutScript
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript2
import org.jenkinsci.plugins.workflow.cps.CpsScript

class NomadDeclarativeAgentScript extends DeclarativeAgentScript2<NomadDeclarativeAgent> {
    NomadDeclarativeAgentScript(CpsScript script, NomadDeclarativeAgent describable) {
        super(script, describable)
    }

    @Override
    void run(Closure body) {
        def args = describable.getAsArgs()
        def resolvedLabel = describable.getLabel() ?: 'nomad'

        script.nomadTemplate(args) {
            script.node(resolvedLabel) {
                CheckoutScript.doCheckout2(script, describable, null) {
                    body.call()
                }
            }
        }
    }
}
