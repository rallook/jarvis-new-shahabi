package com.jarvis.assistant.wake

import kotlin.random.Random

/**
 * Short natural acknowledgements when the user calls Jarvis or interrupts him.
 */
object JarvisAckPhrases {

    private val callAcks = listOf(
        "Yes, sir.",
        "Sir.",
        "Yes, sir?",
        "At your service, sir."
    )

    private val stopAcks = listOf(
        "Yes, sir. What else would you like me to do?",
        "Yes, sir. Anything else?",
        "Understood, sir. What should I do next?",
        "Stopped, sir. How else can I help?",
        "Yes, sir — I'm listening."
    )

    fun forCall(): String = callAcks[Random.nextInt(callAcks.size)]

    fun forStopInterrupt(): String = stopAcks[Random.nextInt(stopAcks.size)]
}
