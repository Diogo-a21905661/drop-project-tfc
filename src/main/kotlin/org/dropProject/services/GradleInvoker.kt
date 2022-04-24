/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 Pedro Alves
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.dropProject.services

import org.dropProject.Constants
import org.dropProject.data.Result
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import dropProject.gradle.tooling.GradleConnector
import java.io.File
import java.io.FileReader
import java.io.StringWriter
import java.util.*

/**
 * NEW: Added to perform Gradle tasks
 * Utility to perform Gradle related tasks.
 */
public class GradleInvoker {    
    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    @Value("\${dropProject.gradle}") //NEW: Not sure where this is supposed to go but i believe its right
    val gradleHome : String = ""

    @Value("\${dropProject.maven.repository}") //NEW: Just use same repository as Maven (who cares)
    val repository : String = ""

    var securityManagerEnabled = true

    fun disableSecurity() {
        securityManagerEnabled = false
    }

    var showOutput = false

    /**
     * Runs the project using Gradle. This function executes the compilation and testing of a submitted project.
     *
     * @param projectFolder is a File containing the project's files
     * @param principalName is a String
     * @param maxMemoryMb is an Int
     *
     * @return a Result
     */
    fun run(projectFolder: File, principalName: String?, maxMemoryMb: Int?) : Result {
        // error check if repository already exists
        if (!File(repository).exists()) {
            val success = File(repository).mkdirs()
            if (!success) {
                LOG.error("Couldn't create the repository folder: $repository")
            }
        }

        val outputLines = ArrayList<String>()

        //NEW: Set connection to Gradle Connector (Tooling API)
        var connector = GradleConnector.newConnector();               
        connector.forProjectDirectory(new File(projectFolder));    

        //NEW: Compile through gradle
        val connection = connector.connect();    
        val build = connection.newBuild();    
        
        //NEW: Compile depending on wether its kotlin or java
        //NEW: These tasks were added via the kotlin plugin in build gradle
        var result: Result = Result()
        try {   
            result = build.compileKotlin();
        }finally {
            connection.close();
        }   

        return Result(resultCode = result.exitCode)
    }
}