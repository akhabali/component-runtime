package com.foo.processor;

import java.io.Serializable;

import java.util.Collection;
import java.util.Set;

// this is the pojo which will be used to represent your data
public class TProcDefaultOutput implements Serializable {
    private int age;

    public int getAge() {
        return age;
    }

    public void setAge(final int age) {
        this.age = age;
    }
}