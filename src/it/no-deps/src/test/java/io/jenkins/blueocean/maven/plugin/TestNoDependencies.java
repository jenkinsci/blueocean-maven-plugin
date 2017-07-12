/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package io.jenkins.blueocean.maven.plugin;

import java.io.File;
import org.junit.Test;

/**
 *
 * @author kzantow
 */
public class TestNoDependencies {
    @Test
    public void test() throws Exception {
        File cwd = new File(System.getProperty("user.dir"));
        File node_modules = new File(cwd, "node_modules");
        assert node_modules.list().length == 0 : "Should not have anything in node_modules";
    }
}
