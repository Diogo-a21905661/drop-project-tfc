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
package org.dropProject.forms

import org.dropProject.dao.Assignment
import org.dropProject.dao.LeaderboardType
import org.dropProject.dao.TestVisibility
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime
import javax.validation.constraints.Min
import javax.validation.constraints.NotEmpty
import javax.validation.constraints.NotNull

/**
 * Enum that represents the submission methods that are available in DP.
 */
enum class SubmissionMethod {
    UPLOAD, GIT
}

/**
 * Represents the contents of a form used to create or edit an [Assignment].
 */
data class AssignmentForm(
        @field:NotEmpty(message = "Error: Assignment Id must not be empty")
        var assignmentId: String? = null,

        @field:NotEmpty(message = "Error: Assignment Name must not be empty")
        var assignmentName: String? = null,

        var assignmentTags: String? = null,  // comma separated list. e.g. "project,18/19"

        var assignmentPackage: String? = null,

        //NEW: Added compiler for the new DP iteration
        @field:NotNull(message = "Error: Compiler must not be empty")
        var compiler: Compiler? = null,

        @field:NotNull(message = "Error: Language must not be empty")
        var language: Language? = null,

        //NEW: Added error so that it cant be Maven and Android at the same time
        if (compiler == Compiler.MAVEN && language == Language.ANDROID) {
            @field:(message = "Error: Maven cannot be used to initialize the Android language")
        }

        @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        var dueDate: LocalDateTime? = null,

        var acceptsStudentTests: Boolean = false,
        var minStudentTests: Int? = null,
        var calculateStudentTestsCoverage: Boolean = false,
        var hiddenTestsVisibility: TestVisibility? = null,
        var mandatoryTestsSuffix: String? = null,
        var cooloffPeriod: Int? = null,

        @field:Min(value=32, message="Error: Max memory must be >= 32")
        var maxMemoryMb: Int? = null,

        var leaderboardType: LeaderboardType? = null,

        var assignees: String? = null,

        var editMode: Boolean = false,

        @field:NotNull(message = "Error: Submission Method must not be empty")
        var submissionMethod: SubmissionMethod? = null,

        @field:NotEmpty(message = "Error: Git repository must not be empty")
        var gitRepositoryUrl: String? = null,

        var acl: String? = null
)
    
