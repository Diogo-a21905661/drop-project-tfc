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
package org.dropProject.dao

import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.Id

enum class Indicator(val code: String, val description: String) {
    PROJECT_STRUCTURE("PS", "Project Structure"),
    COMPILATION("C", "Compilation"),
    CHECKSTYLE("CS", "Code Quality (Checkstyle)"),
    TEACHER_UNIT_TESTS("TT", "Teacher Unit Tests"),
    STUDENT_UNIT_TESTS("ST", "Student Unit Tests"),
    HIDDEN_UNIT_TESTS("HT", "Teacher Hidden Unit Tests");

    companion object {
        fun getIndicator(code: String?) : Indicator? {

            if (code == null) {
                return null
            }

            for (indicator in Indicator.values()) {
                if (code.equals(indicator.code)) {
                    return indicator
                }
            }
            throw IllegalArgumentException("No matching Indicator for code ${code}")
        }

    }
}


@Entity
data class SubmissionReport(
        @Id @GeneratedValue
        val id: Long = 0,
        val submissionId: Long = 0,  // FK for Submission.id
        var reportKey: String? = null,  // Indicator::code
        val reportValue: String? = null
) {

    fun getReportKey(): Indicator? {
        return Indicator.getIndicator(reportKey)
    }

    fun setReportKey(indicator: Indicator) {
        reportKey = indicator.code
    }
}