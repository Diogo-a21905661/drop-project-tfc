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

import org.dropProject.services.AssignmentValidator
import com.thoughtworks.qdox.JavaProjectBuilder
import com.thoughtworks.qdox.model.impl.DefaultJavaMethod
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.dropProject.Constants
import org.dropProject.dao.Assignment
import org.dropProject.dao.Language
import org.dropProject.dao.TestVisibility
import org.dropProject.extensions.toEscapedHtml
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Service
import java.io.File
import java.io.FileReader

/**
 * This class performs validation of the assignments created by teachers, in order to make sure that they have the
 * correct formats and include the expected plugins.
 *
 * @property report is a List of [Info], containing warnings about the problems that were identified during the validation
 * @property testMethods is a List of String, containing the names of the JUnit test methods that were found in the assignment's
 * test classes. Each String will contain the name of a test method, prefixed by the name of the class where it was declared.
 */
@Service
@Scope("prototype")
class AssignmentValidatorMaven : AssignmentValidator() {
    
    /**
     * Validates the [Assignment].
     *
     * @param assignmentFolder is a File, representing the file system folder where the assignment's code is stored
     * @param assignment is the Assignment to validate
     */
    override fun validate(assignmentFolder: File, assignment: Assignment) {
        val pomFile = File(assignmentFolder, "pom.xml")
 
        //Check if file exists
        if (!pomFile.exists()) {
            report.add(Info(InfoType.ERROR, "Assignment must have a pom.xml file.",
            "Check <a href=\"https://github.com/palves-ulht/sampleJavaAssignment\">" +
                    "https://github.com/palves-ulht/sampleJavaAssignment</a> for an example"))
            return
        } else {
            report.add(Info(InfoType.INFO, "Assignment has a pom.xml"))
        }
        
        val reader = MavenXpp3Reader()
        val model = reader.read(FileReader(pomFile))

        validateCurrentUserIdSystemVariable(assignmentFolder, model)
        validateUntrimmedStacktrace(model)
        validateProperTestClasses(assignmentFolder, assignment)
        if (assignment.maxMemoryMb != null) {
            validatePomPreparedForMaxMemory(model)
        }
        validatePomPreparedForCoverage(model, assignment)
    }

    // tests that the assignment is ready to use the system property "dropProject.currentUserId"
    private fun validateCurrentUserIdSystemVariable(assignmentFolder: File, pomModel: Model) {

        // first check if the assignment code is referencing this property
        if (searchAllSourceFilesWithinFolder(assignmentFolder, "System.getProperty(\"dropProject.currentUserId\")")) {
            val surefirePlugin = pomModel.build.plugins.find { it.artifactId == "maven-surefire-plugin" }
            if (surefirePlugin == null ||
                    surefirePlugin.configuration == null ||
                    !surefirePlugin.configuration.toString().contains("<argLine>\${dp.argLine}</argLine>")) {

                addWarningAboutSurefireWithArgline("POM file is not prepared to use the 'dropProject.currentUserId' system property")

            } else {
                report.add(Info(InfoType.INFO, "POM file is prepared to set the 'dropProject.currentUserId' system property"))
            }
        } else {
            report.add(Info(InfoType.INFO, "Doesn't use the 'dropProject.currentUserId' system property"))
        }
    }

    // tests that the surefire-plugin is showing full stacktraces
    private fun validateUntrimmedStacktrace(pomModel: Model) {

        val surefirePlugin = pomModel.build.plugins.find { it.artifactId == "maven-surefire-plugin" }
        if (surefirePlugin != null && surefirePlugin.version != null) {

            if (surefirePlugin.configuration == null ||
                    !surefirePlugin.configuration.toString().contains("<trimStackTrace>false</trimStackTrace>")) {

                report.add(Info(InfoType.WARNING, "POM file is not configured to prevent stacktrace trimming on junit errors",
                        "By default, the maven-surefire-plugin trims stacktraces (version >= 2.2), which may " +
                                "complicate students efforts to understand junit reports. " +
                                "It is suggested to set the 'trimStackStrace' flag to false, like this:<br/><pre>" +
                                """
                                    |<plugin>
                                    |   <groupId>org.apache.maven.plugins</groupId>
                                    |   <artifactId>maven-surefire-plugin</artifactId>
                                    |   <version>2.19.1</version>
                                    |   <configuration>
                                    |       ...
                                    |       <trimStackTrace>false</trimStackTrace>
                                    |   </configuration>
                                    |</plugin>
                                    """.trimMargin().toEscapedHtml()
                                + "</pre>"
                ))

            } else {
                report.add(Info(InfoType.INFO, "POM file is prepared to prevent stacktrace trimming on junit errors"))
            }
        } else {
            report.add(Info(InfoType.INFO, "POM file is prepared to prevent stacktrace trimming on junit errors"))
        }
    }


    private fun validatePomPreparedForMaxMemory(pomModel: Model) {
        val surefirePlugin = pomModel.build.plugins.find { it.artifactId == "maven-surefire-plugin" }
        if (surefirePlugin == null ||
                surefirePlugin.configuration == null ||
                !surefirePlugin.configuration.toString().contains("<argLine>\$\\{dp.argLine\\}</argLine>")) {

            addWarningAboutSurefireWithArgline("POM file is not prepared to set the max memory available")

        } else {
            report.add(Info(InfoType.INFO, "POM file is prepared to define the max memory for each submission"))
        }
    }

    private fun addWarningAboutSurefireWithArgline(message: String) {
        report.add(Info(InfoType.WARNING, message,
                "The system property 'dropProject.currentUserId' only works if you add the following lines to your pom file:<br/><pre>" +
                        """
                        |<plugin>
                        |   <groupId>org.apache.maven.plugins</groupId>
                        |   <artifactId>maven-surefire-plugin</artifactId>
                        |   <version>2.19.1</version>
                        |   <configuration>
                        |      <argLine>$\{dp.argLine}</argLine>
                        |   </configuration>
                        |</plugin>
                        """.trimMargin().replace("\\","").toEscapedHtml()
                        + "</pre>"

        ))
    }

    /**
     * Performs [Assignment] validations related with the calculation the test coverage of student's own tests.
     * Namely, it validates if an assignment that is configured to calculate the coverage contains the necessary plugins
     * in the respective pom.xml file (and vice versa).
     *
     * @param pomModel is a Model
     * @param assignment is the Assignment to validate
     */
    private fun validatePomPreparedForCoverage(pomModel: Model, assignment: Assignment) {
        val surefirePlugin = pomModel.build.plugins.find { it.artifactId == "jacoco-maven-plugin" }
        val packagePath = assignment.packageName?.replace(".","/").orEmpty()
        if (surefirePlugin != null) {
            if (assignment.calculateStudentTestsCoverage) {
                if (surefirePlugin.configuration == null ||
                        !surefirePlugin.configuration.toString().contains("<include>${packagePath}/*</include>")) {
                    report.add(Info(InfoType.ERROR, "jacoco-maven-plugin (used for coverage) has a configuration problem",
                            "The jacoco-maven-plugin must include a configuration that includes only the classes of " +
                                    "the assignment package. Please fix this in your assignment POM file. " +
                                    "Configuration example:<br/><pre>" +
                                    """
                                    |<plugin>
                                    |    <groupId>org.jacoco</groupId>
                                    |    <artifactId>jacoco-maven-plugin</artifactId>
                                    |    <version>0.8.2</version>
                                    |    <configuration>
                                    |        <includes>
                                    |            <include>${packagePath}/*</include>
                                    |        </includes>
                                    |    </configuration>
                                    |    <executions>
                                    |        <execution>
                                    |            <goals>
                                    |                <goal>prepare-agent</goal>
                                    |            </goals>
                                    |        </execution>
                                    |        <execution>
                                    |            <id>generate-code-coverage-report</id>
                                    |            <phase>test</phase>
                                    |            <goals>
                                    |                <goal>report</goal>
                                    |            </goals>
                                    |        </execution>
                                    |    </executions>
                                    |</plugin>
                                    """.trimMargin().toEscapedHtml()
                                    + "</pre>"

                    ))
                } else {
                    report.add(Info(InfoType.INFO, "POM file is prepared to calculate coverage"))
                }
            } else {
                report.add(Info(InfoType.WARNING, "POM file includes a plugin to calculate coverage but the " +
                        "assignment has the flag 'Calculate coverage of student tests?' set to 'No'",
                        "For performance reasons, you should remove the jacoco-maven-plugin from your POM file"))
            }
        } else {
            if (assignment.calculateStudentTestsCoverage) {
                report.add(Info(InfoType.ERROR, "POM file is not prepared to calculate coverage",
                        "The assignment has the flag 'Calculate coverage of student tests?' set to 'Yes' " +
                                "but the POM file doesn't include the jacoco-maven-plugin. Please add the following " +
                                "lines to your pom file:<br/><pre>" +
                                """
                                    |<plugin>
                                    |    <groupId>org.jacoco</groupId>
                                    |    <artifactId>jacoco-maven-plugin</artifactId>
                                    |    <version>0.8.2</version>
                                    |    <configuration>
                                    |        <includes>
                                    |            <include>${packagePath}/*</include>
                                    |        </includes>
                                    |    </configuration>
                                    |    <executions>
                                    |        <execution>
                                    |            <goals>
                                    |                <goal>prepare-agent</goal>
                                    |            </goals>
                                    |        </execution>
                                    |        <execution>
                                    |            <id>generate-code-coverage-report</id>
                                    |            <phase>test</phase>
                                    |            <goals>
                                    |                <goal>report</goal>
                                    |            </goals>
                                    |        </execution>
                                    |    </executions>
                                    |</plugin>
                                """.trimMargin().toEscapedHtml()
                                + "</pre>"))
                println("")
            }
        }
    } 
}
