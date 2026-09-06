package com.endsustain.combat;

import com.endsustain.EndSustain;
import net.minecraft.world.entity.LivingEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Agent 保险层桥接（游戏侧）：
 * 当强杀链所有路径结束后实体仍然存活时，生成带 Agent-Class 清单的临时 jar，
 * 并启动独立进程 attach 到当前游戏 JVM，由 KillAgent 反射移除实体并改写其字节码。
 */
public final class AgentBridge {
    private static volatile Path agentJar;

    private AgentBridge() {
    }

    /** 启动外部 agent 保险层（异步）。 */
    public static void forceKillViaAgent(LivingEntity target) {
        try {
            Path jar = ensureAgentJar();
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = pid.indexOf('@');
            if (at > 0) {
                pid = pid.substring(0, at);
            }
            String javaBin = System.getProperty("java.home") + "/bin/java";
            String internalName = target.getClass().getName();
            new ProcessBuilder(javaBin, "-cp", jar.toString(),
                    "com.endsustain.agent.Attacher", pid, jar.toString(), internalName)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            EndSustain.LOGGER.warn("[终焉强杀] Agent 保险层已启动：pid={}，目标类={}",
                    pid, internalName);
        } catch (Throwable t) {
            EndSustain.LOGGER.error("[终焉强杀] Agent 保险层启动失败", t);
        }
    }

    /** 生成临时 agent jar：复制自身 jar 并追加 Agent-Class 清单属性。 */
    private static synchronized Path ensureAgentJar() throws Exception {
        if (agentJar != null && Files.exists(agentJar)) return agentJar;
        Path self = Path.of(AgentBridge.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Agent-Class", "com.endsustain.agent.KillAgent");
        attributes.putValue("Launcher-Agent-Class", "com.endsustain.agent.KillAgent");
        attributes.putValue("Can-Redefine-Classes", "true");
        attributes.putValue("Can-Retransform-Classes", "true");
        Path temp = Files.createTempFile("endsustain-agent-", ".jar");
        try (JarInputStream in = new JarInputStream(Files.newInputStream(self), false);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(temp), manifest)) {
            byte[] buffer = new byte[8192];
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                copyStream(in, out, buffer);
                out.closeEntry();
            }
        }
        agentJar = temp;
        return temp;
    }

    private static void copyStream(InputStream in, OutputStream out, byte[] buffer) throws IOException {
        int length;
        while ((length = in.read(buffer)) > 0) {
            out.write(buffer, 0, length);
        }
    }
}
