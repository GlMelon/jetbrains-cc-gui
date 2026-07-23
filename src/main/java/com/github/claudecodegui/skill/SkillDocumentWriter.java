package com.github.claudecodegui.skill;

import java.io.IOException;
import java.nio.file.Path;

/** Atomic writer abstraction, injectable for rollback tests. */
interface SkillDocumentWriter {

    Path write(Path target, String content) throws IOException;

    void restore(Path target, Path backup) throws IOException;
}
