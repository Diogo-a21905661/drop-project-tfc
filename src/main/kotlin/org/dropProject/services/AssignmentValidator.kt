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
open class AssignmentValidator {

    enum class InfoType { INFO, WARNING, ERROR }

    /**
     * Represents an assignment validation message that is used to report problems with the [Assignment] being validated.
     */
    data class Info(val type: InfoType, val message: String, val description: String = "")

    val report = mutableListOf<Info>()
    val testMethods = mutableListOf<String>()

    /**
     * Picks between which [Assignment] validator we will be using.
     * That depends on which compiler is being used
     *
     * @param assignmentFolder is a File, representing the file system folder where the assignment's code is stored
     * @param assignment is the Assignment to validate
     */
    abstract fun validate(assignmentFolder: File, assignment: Assignment)

    /**
     * Validates an [Assignment]'s test files to determine if the test classes are respecting the expected
     * formats (for example, ensure that the respective filename starts with the correct prefix).
     *
     * @param assignmentFolder is a File, representing the file system folder where the assignment's code is stored
     * @param assignment is the Assignment to validate
     */
    protected fun validateProperTestClasses(assignmentFolder: File, assignment: Assignment) {

        var correctlyPrefixed = true

        val testClasses = File(assignmentFolder, "src/test")
                .walkTopDown()
                .filter { it -> it.name.startsWith(Constants.TEST_NAME_PREFIX) }
                .toList()

        if (testClasses.isEmpty()) {
            report.add(Info(InfoType.WARNING, "You must have at least one test class on src/test/** whose name starts with ${Constants.TEST_NAME_PREFIX}"))
        } else {
            report.add(Info(InfoType.INFO, "Found ${testClasses.size} test classes"))

            if (assignment.language == Language.JAVA) {
                val builder = JavaProjectBuilder()

                // for each test class, check if all the @Test define a timeout
                var invalidTestMethods = 0
                var validTestMethods = 0
                for (testClass in testClasses) {
                    val testClassSource = builder.addSource(testClass)
                    testClassSource.classes.forEach {
                        it.methods.forEach {
                            val methodName = it.name
                            if (!it.annotations.any { it.type.fullyQualifiedName == "org.junit.Ignore" ||
                                            it.type.fullyQualifiedName == "Ignore" }) {  // ignore @Ignore
                                it.annotations.forEach {
                                    if (it.type.fullyQualifiedName == "org.junit.Test" ||  // found @Test
                                            it.type.fullyQualifiedName == "Test") {  // qdox doesn't handle import *
                                        if (it.getNamedParameter("timeout") == null) {
                                            invalidTestMethods++
                                        } else {
                                            validTestMethods++
                                        }
                                        testMethods.add(testClassSource.classes.get(0).name + ":" + methodName)
                                    }
                                }
                            }
                        }
                    }
                }


                if (invalidTestMethods + validTestMethods == 0) {
                    report.add(Info(InfoType.WARNING, "You haven't defined any test methods.", "Use the @Test(timeout=xxx) annotation to mark test methods."))
                }

                if (invalidTestMethods > 0) {
                    report.add(Info(InfoType.WARNING, "You haven't defined a timeout for ${invalidTestMethods} test methods.",
                            "If you don't define a timeout, students submitting projects with infinite loops or wait conditions " +
                                    "will degrade the server. Example: Use @Test(timeout=500) to set a timeout of 500 miliseconds."))
                } else if (validTestMethods > 0) {
                    report.add(Info(InfoType.INFO, "You have defined ${validTestMethods} test methods with timeout."))
                }
            }
        }

        if (assignment.acceptsStudentTests) {
            for (testClass in testClasses) {
                if (!testClass.name.startsWith(Constants.TEACHER_TEST_NAME_PREFIX)) {
                    report.add(Info(InfoType.WARNING, "${testClass} is not valid for assignments which accept student tests.",
                            "All teacher tests must be prefixed with ${Constants.TEACHER_TEST_NAME_PREFIX} " +
                                    "(e.g., ${Constants.TEACHER_TEST_NAME_PREFIX}Calculator" +
                                    " instead of ${Constants.TEST_NAME_PREFIX}Calculator)"))
                    correctlyPrefixed = false
                }
            }

            if (correctlyPrefixed) {
                report.add(Info(InfoType.INFO, "All test classes correctly prefixed"));
            }
        }

        // check if it has hidden tests and the visibility policy has not been set
        val hasHiddenTests = File(assignmentFolder, "src/test")
                .walkTopDown()
                .any { it -> it.name.startsWith(Constants.TEACHER_HIDDEN_TEST_NAME_PREFIX) }

        if (hasHiddenTests) {
            if (assignment.hiddenTestsVisibility == null) {
                report.add(Info(InfoType.ERROR, "You have hidden tests but you didn't set their visibility to students.",
                        "Edit this assignment and select an option in the field 'Hidden tests' to define if the results should be " +
                                "completely hidden from the students or if some information is shown."))
            } else {
                val message = when (assignment.hiddenTestsVisibility) {
                        TestVisibility.HIDE_EVERYTHING ->  "The results will be completely hidden from the students."
                        TestVisibility.SHOW_OK_NOK -> "Students will only see if they pass all the hidden tests or not."
                        TestVisibility.SHOW_PROGRESS -> "Students will only see the number of tests passed."
                        null -> throw Exception("This shouldn't be possible!")
                }
                report.add(Info(InfoType.INFO, "You have hidden tests. ${message}"))
            }
        }
    }

    //Get all code files within folder
    protected fun searchAllSourceFilesWithinFolder(folder: File, text: String): Boolean {
        return folder.walkTopDown()
                .filter { it -> it.name.endsWith(".java") || it.name.endsWith(".kt") }
                .any {
                    it.readText().contains(text)
                }
    }
}
