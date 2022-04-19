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
import org.dropProject.data.GradleResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileReader
import java.io.StringWriter
import java.util.*

/**
 * NEW: Added to perform Gradle tasks
 * Utility to perform Gradle related tasks.
 */
public class GradleConnector {    
    val connector : org.gradle.tooling.GradleConnector; //NEW: Tooling API connector to tasks
    
    val LOG = LoggerFactory.getLogger(this.javaClass.name)

    @Value("\${dropProject.maven.home}")
    val home : String = ""

    @Value("\${dropProject.maven.repository}")
    val repository : String = ""

    var securityManagerEnabled = true

    fun disableSecurity() {
        securityManagerEnabled = false
    }

    var showOutput = false

    //Gradle Invoker constructor
    fun GradleInvoker(String gradleInstallationDir, String projectDir) {        
        File newGradleInstallationDir = new File(gradleInstallationDir);        
        
        connector = org.gradle.tooling.GradleConnector.newConnector();        
        connector.useInstallation(newGradleInstallationDir);        
        connector.forProjectDirectory(new File(projectDir));    
    }    
  
    //Get current Gradle version
    fun getGradleVersion() : String {        
        return GradleVersion.current().getVersion();    
    }    
  
    //Get Gradle task names
    fun getGradleTaskNames() : List<String> {        
        List<String> taskNames = new ArrayList<>();        
        List<GradleTask> tasks = getGradleTasks();        
       
        return tasks.stream().map(task -> task.getName()).collect(Collectors.toList());    
    }    

    //Get Gradle tasks
    fun getGradleTasks() :  List<GradleTask> {        
        List<GradleTask> tasks = new ArrayList<>();        
        ProjectConnection connection = connector.connect();        
        
        try {            
            GradleProject project = connection.getModel(GradleProject.class);            
            for (GradleTask task : project.getTasks()) {                
                tasks.add(task);            
            }        
        } finally {            
            connection.close();        
        }        

        return tasks;    
    }

    //Build project using Gradle without using tasks
    fun buildProject() : boolean {    
        ProjectConnection connection = connector.connect();    
        BuildLauncher build = connection.newBuild();    
        
        try {
            build.run();
        }finally {
            connection.close();
        }   

        return true;
    }

    //Build project using Gradle while using tasks
    fun buildProjectWithTasks(String tasks) : boolean {   
        ProjectConnection connection = connector.connect();    
        BuildLauncher build = connection.newBuild();    
        build.forTasks(tasks);    
        
        try {        
            build.run();    
        } finally {       
            connection.close();   
        }

        return true;
    }

    /**
     * Runs the project using Gradle. This function executes the compilation and testing of a submitted project.
     *
     * @param projectFolder is a File containing the project's files
     * @param principalName is a String
     * @param maxMemoryMb is an Int
     *
     * @return a GradleResult
     */
    fun run(projectFolder: File, principalName: String?, maxMemoryMb: Int?) : GradleResult {

        // error check if repository already exists
        if (!File(repository).exists()) {
            val success = File(repository).mkdirs()
            if (!success) {
                LOG.error("Couldn't create the repository folder: $repository")
            }
        }

        val outputLines = ArrayList<String>()

        var dpArgLine = ""
        if (maxMemoryMb != null) {
            dpArgLine += " -Xmx${maxMemoryMb}M"
        }

        // check principal name and security manager (output)
        if (principalName != null) {
            dpArgLine += " -DdropProject.currentUserId=${principalName}"
        }
        if (securityManagerEnabled) {
            dpArgLine += " -Djava.security.manager=org.dropProject.security.SandboxSecurityManager"
        }

        var numLines = 0
        request.setOutputHandler {
            line -> run {
                if (showOutput) {
                    println(">>> ${line}")
                }
                numLines++
                if (numLines < Constants.TOO_MUCH_OUTPUT_THRESHOLD) {
                    outputLines.add(line)
                }
                if (numLines == Constants.TOO_MUCH_OUTPUT_THRESHOLD) {
                    outputLines.add("*** Trimmed here by DP ***")
                }
            }
        }

        //Have to put Gradle compilation here

        return GradleResult(resultCode = result.exitCode, outputLines = outputLines)
    }
}