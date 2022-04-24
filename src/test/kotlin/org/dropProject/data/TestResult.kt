/*-
 * ========================LICENSE_START=================================
 * DropProject
 * %%
 * Copyright (C) 2019 - 2021 Pedro Alves
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
package org.dropProject.data

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class TestResult {
    @Test
    fun TestResult() {
        val outputLines = mutableListOf<String>()
        var result = Result(100, outputLines, true)
        assertTrue(result.expiredByTimeout)
        assertEquals(100, result.resultCode)

        var result2 = Result(200, outputLines, false)
        assertFalse(result2.expiredByTimeout)
        assertEquals(200, result2.resultCode)

        result2.expiredByTimeout = true
        assertTrue(result2.expiredByTimeout)
    }
}
