/*
 * Copyright 2013-2015 Bud Byrd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.budjb.rabbitmq.*
import com.budjb.rabbitmq.connection.ConnectionManager
import com.budjb.rabbitmq.consumer.ConsumerManager
import com.budjb.rabbitmq.converter.MessageConverterManager
import com.budjb.rabbitmq.publisher.RabbitMessagePublisherImpl
import org.apache.log4j.Logger
import org.codehaus.groovy.grails.commons.GrailsClass

class RabbitmqNativeGrailsPlugin {
    /**
     * Version of the plugin.
     */
    def version = "3.0.3"

    /**
     * The version or versions of Grails the plugin is designed for.
     */
    def grailsVersion = "2.0 > *"

    /**
     * Title/name of the plugin.
     */
    def title = "Rabbitmq Native Plugin"

    /**
     * Author's name.
     */
    def author = 'Bud Byrd'

    /**
     * Author email address.
     */
    def authorEmail = 'bud.byrd@gmail.com'

    /**
     * Description of the plugin.
     */
    def description = 'The native RabbitMQ Grails plugin provides easily consumable messaging functionality.'

    /**
     * URL to the plugin's documentation.
     */
    def documentation = "http://budjb.github.io/grails-rabbitmq-native/doc/manual/guide/index.html"

    /**
     * Project license.
     */
    def license = "APACHE"

    /**
     * Location of the plugin's issue tracker.
     */
    def issueManagement = [system: 'GITHUB', url: 'https://github.com/budjb/grails-rabbitmq-native/issues']

    /**
     * Online location of the plugin's browseable source code.
     */
    def scm = [url: 'https://github.com/budjb/grails-rabbitmq-native']

    /**
     * Load order.
     */
    def loadAfter = ['controllers', 'services', 'domains', 'hibernate', 'spring-security-core']

    /**
     * Excluded files.
     */
    def pluginExcludes = [
        'test/**',
        'grails-app/rabbit-consumers/**',
        'grails-app/conf/Config.groovy',
        'src/groovy/com/budjb/rabbitmq/test/**',
        'src/docs/**'
    ]

    /**
     * Resources this plugin should monitor changes for.
     */
    def watchedResources = [
        'file:./grails-app/rabbit-converters/**Converter.groovy',
        'file:./grails-app/rabbit-consumers/**Consumer.groovy',
        'file:./plugins/*/grails-app/rabbit-converters/**Converter.groovy',
        'file:./plugins/*/grails-app/rabbit-consumers/**Consumer.groovy'
    ]

    /**
     * Custom artefacts
     */
    def artefacts = [
        new MessageConverterArtefactHandler(),
        new MessageConsumerArtefactHandler()
    ]

    /**
     * Logger.
     */
    Logger log = Logger.getLogger('com.budjb.rabbitmq.RabbitmqNativeGrailsPlugin')

    /**
     * Spring actions.
     */
    def doWithSpring = {
        // Create the null rabbit context bean
        'nullRabbitContext'(NullRabbitContext)

        // Create the live rabbit context bean
        'rabbitContextImpl'(RabbitContextImpl) { bean ->
            bean.autowire = true
        }

        // Create the proxy rabbit context bean
        'rabbitContext'(RabbitContextProxy) {
            if (application.config.rabbitmq.enabled == false) {
                log.warn("The rabbitmq-native plugin has been disabled by the application's configuration.")
                target = ref('nullRabbitContext')
            }
            else {
                target = ref('rabbitContextImpl')
            }
        }

        "connectionManager"(ConnectionManager) { bean ->
            bean.autowire = true
        }

        "queueBuilder"(QueueBuilder) { bean ->
            bean.autowire = true
        }

        "messageConverterManager"(MessageConverterManager) { bean ->
            bean.autowire = true
        }

        "consumerManager"(ConsumerManager) { bean ->
            bean.autowire = true
        }

        "rabbitMessagePublisher"(RabbitMessagePublisherImpl) { bean ->
            bean.autowire = true
        }

        // Create application-provided converter beans
        application.messageConverterClasses.each { GrailsClass clazz ->
            "${clazz.propertyName}"(clazz.clazz) { bean ->
                bean.autowire = true
            }
        }

        // Create consumer beans
        application.messageConsumerClasses.each { GrailsClass clazz ->
            "${clazz.propertyName}"(clazz.clazz) { bean ->
                bean.autowire = true
            }
        }
    }

    /**
     * Application context actions.
     */
    def doWithApplicationContext = { applicationContext ->
        // Do nothing if the plugin's disabled.
        if (application.config.rabbitmq.enabled == false) {
            return
        }

        // Load and start the rabbit service, without starting consumers.
        RabbitContext rabbitContext = applicationContext.getBean('rabbitContext')
        rabbitContext.load()
        rabbitContext.start(true)
    }

    /**
     * Handle Grails service reloads.
     */
    def onChange = { event ->
        // Do nothing if the plugin's disabled.
        if (application.config.rabbitmq.enabled == false) {
            return
        }

        // Bail if no context
        if (!event.ctx) {
            return
        }

        // Check for reloaded message converters
        if (application.isArtefactOfType(MessageConverterArtefactHandler.TYPE, event.source)) {
            // Re-register the bean
            GrailsMessageConverterClass converterClass = application.addArtefact(MessageConverterArtefactHandler.TYPE, event.source)
            beans {
                "${converterClass.propertyName}"(converterClass.clazz) { bean ->
                    bean.autowire = true
                }
            }.registerBeans(event.ctx)

            // Restart the rabbit context
            RabbitContext context = event.ctx.getBean('rabbitContext')
            context.restart()
            return
        }

        // Check for reloaded message consumers
        if (application.isArtefactOfType(MessageConsumerArtefactHandler.TYPE, event.source)) {
            // Re-register the bean
            GrailsMessageConverterClass consumerClass = application.addArtefact(MessageConsumerArtefactHandler.TYPE, event.source)
            beans {
                "${consumerClass.propertyName}"(consumerClass.clazz) { bean ->
                    bean.autowire = true
                }
            }.registerBeans(event.ctx)

            // Restart the consumers
            RabbitContext context = event.ctx.getBean('rabbitContext')
            context.restart()
            return
        }
    }

    /**
     * Handle configuration changes.
     */
    def onConfigChange = { event ->
        // Do nothing if the plugin's disabled.
        if (application.config.rabbitmq.enabled == false) {
            return
        }

        RabbitContext context = event.ctx.getBean('rabbitContext')
        context.restart()
    }
}
