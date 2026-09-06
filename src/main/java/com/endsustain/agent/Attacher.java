package com.endsustain.agent;

/**
 * 外部进程入口：attach 到游戏 JVM 并加载 KillAgent。
 * 用法：java -cp <agentJar> com.endsustain.agent.Attacher <pid> <agentJarPath> <targetInternalName>
 */
public final class Attacher {

    private Attacher() {
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: Attacher <pid> <agentJarPath> <targetInternalName>");
            return;
        }
        try {
            com.sun.tools.attach.VirtualMachine vm =
                    com.sun.tools.attach.VirtualMachine.attach(args[0]);
            try {
                vm.loadAgent(args[1], args[2]);
            } finally {
                vm.detach();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
