
# 📘 Relatório de Desenvolvimento

## 1. 🚀 Como executar o projeto

Para executar o sistema, navegue até o diretório do projeto e rode:

``
java Sistema
``

Isso iniciará o sistema operacional simulado, que inclui um CLI interativo para gerenciar processos, memória e executar programas.


---

## 2. ⚙️ Implementação de Concorrência Real

### 2.1 🔄 Arquitetura Multi-thread

O sistema agora opera com 4 threads principais:

* Thread Shell (CLI): Interface com o usuário

* Thread Escalonador: Gerencia filas de processos

* Thread CPU: Executa instruções

* Thread Console: Processa E/S assíncrona



---

### 2.2 🔐 Mecanismo de Sincronização

```
// Semáforos para coordenação entre threads
public Semaphore semaCPU = new Semaphore(0);
public Semaphore semaEscalonador = new Semaphore(0);
public Semaphore semaConsole = new Semaphore(0);
```

---

### 2.3 🔁 Escalonamento Preemptivo

Quantum configurado para 4 instruções:

```
// Controle do quantum na CPU
private int instrucoesExecutadas = 0;
private final int DELTA_INSTRUCOES = 4;
```

---

## 3. 💾 Sistema de E/S Assíncrona

### 3.1 🖥️ Thread de Console Dedicada

```
public class ThreadConsole implements Runnable {
    public void run() {
        while(true) {
            IORequest req = so.filaPedidosConsole.take();
            // Processa operações IN/OUT
            hw.cpu.setIOInterrupt(pcb.id);
        }
    }
}
```

---

### 3.2 🧩 Tratamento de Syscalls

Chamadas de sistema bloqueiam o processo e liberam a CPU:

```
case SYSCALL:
    sysCall.handle(); // Desvia para tratamento de E/S
    pc++;
    break;
```

---

## 4. 🧠 Implementação de Memória Virtual

### 4.1 📄 Estrutura Page Table Entry
```
public static class PageTableEntry {
    public int frameNumber;   // Frame físico
    public boolean validBit;  // Página na memória?
    public boolean dirtyBit;  // Página modificada?
    public int diskAddress;   // Endereço no swap
}
```

---

### 4.2 💤 Paginação por Demanda (Lazy Loading)
```
// Carrega apenas a página 0 inicialmente
pcb.tabelaPaginas[0].validBit = true;

// Demais páginas são inválidas no início
for (int i = 1; i < numPaginas; i++) {
    pcb.tabelaPaginas[i].validBit = false;
}
```

---

### 4.3 ⚠️ Detecção de Page Faults
```
private int translate(int logicalAddress) {
    PageTableEntry entry = regTabelaPaginas[pageNumber];

    if (!entry.validBit) {
        // PAGE FAULT detectado
        pageFaultLogicalAddress = logicalAddress;
        irpt = Interrupts.intPageFault;
        return -1;
    }

    // Tradução normal...
}
```

---

### 4.4 💽 Thread de Disco Virtual
```
public class ThreadDiscoVM implements Runnable {
    public void run() {
        while(true) {
            IORequestVM req = so.filaPedidosDiscoVM.take();
            Thread.sleep(100); // Simula latência do disco
            // Processa LOAD_PAGE ou SAVE_PAGE
        }
    }
}
```

---

### 4.5 🔄 Substituição de Páginas (Vitimização FIFO)
```
public int escolheVitima() {
    if (fifoQueue.isEmpty()) return -1;
    return fifoQueue.pollFirst(); // FIFO: mais antigo primeiro
}
```

---

### 4.6 🛠️ Tratamento de Page Faults
```
private void handlePageFault() {
    // 1. Verifica frame livre
    // 2. Aplica vitimização (se necessário)
    // 3. Verifica dirty bit da vítima
    // 4. Salva página (se necessário)
    // 5. Carrega página faltante
}
```

---

## 5. 🔔 Novas Interrupções do Sistema

### 5.1 📡 Interrupções Adicionadas
```
public enum Interrupts {
    // ... interrupções existentes
    intPageFault,          
    intFimCargaDiscoVM,    
    intFimSalvaDiscoVM     
}

```
---

### 5.2 🩸 Dirty Bit
```
case STD:
    markPageDirty(ir.p);
    break;

case STX:
    markPageDirty(logicalAddrSTX);
    break;
```

---

## 6. 🖥️ Comandos Aprimorados do CLI

### 6.1 🛠️ Comandos

new [prog] — Cria processo com lazy loading

ps — Exibe processos incluindo estado BLOCKED

dump [id] — Mostra tabela de páginas completa

meminfo — Exibe informações de memória virtual



---

### 6.2 📄 Exemplo do comando dump

```
Página 0 -> Frame 3 [V:true D:false Disk:0]
Página 1 -> Frame -1 [V:false D:false Disk:16]
Página 2 -> Frame 5 [V:true D:true Disk:32]
```

---

## 7. 🔂 Fluxo de Execução do Sistema

### 7.1 🔧 Inicialização

Instancia hardware e SO

Inicia threads (escalonador, CPU, console, disco VM)

CLI roda na thread principal



---

### 7.2 🧬 Ciclo de Vida do Processo

Criação: apenas página 0 é carregada

Execução: page faults tratados sob demanda

E/S: processo bloqueado e CPU liberada

Término: páginas desalocadas



---

### 7.3 📘 Tratamento de Page Fault

1. CPU detecta página inválida
2. Gera interrupção intPageFault
3. Processo é bloqueado
4. Disco VM carrega página correta
5. Processo retorna para READY
