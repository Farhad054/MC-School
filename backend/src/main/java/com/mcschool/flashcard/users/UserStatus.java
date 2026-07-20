package com.mcschool.flashcard.users;

public enum UserStatus {
    /** Account was created by an admin/teacher; the owner has not set a password yet. */
    INVITED,
    /** The owner accepted the invitation and can log in. */
    ACTIVE
}
