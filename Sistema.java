// PUCRS - Escola Politécnica - Sistemas Operacionais
// Prof. Fernando Dotti
// Código fornecido como parte da solução do projeto de Sistemas Operacionais

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.LinkedBlockingQueue;

public class Sistema {

    // ------------------- ESTADOS DO PROCESSO -------------------
    public enum ProcessState {
        NEW, READY, RUNNING, BLOCKED, TERMINATED
    }

    // ------------------- PAGE TABLE ENTRY -------------------
    public static class PageTableEntry {
        public int frameNumber;   // Frame físico, se válido
        public boolean validBit;  // Se a página está na memória principal
        public boolean dirtyBit;  // Se a página foi modificada
        public int diskAddress;   // Endereço no disco (swap)

        public PageTableEntry() {
            this.frameNumber = -1;
            this.validBit = false;
            this.dirtyBit = false;
            this.diskAddress = -1;
        }
    }

    // ------------------- PROCESS CONTROL BLOCK (PCB) -------------------
    public class PCB {
        public int id;
        public int pc;
        public int[] reg;
        public PageTableEntry[] tabelaPaginas;  // Alterado para PageTableEntry[]
        public ProcessState estado;
        public String programName;
        public Word[] imagem;  // Para armazenar a imagem do programa para lazy loading

        private static int nextId = 0;

        public PCB(int numPaginas, String _programName, Word[] _imagem) {
            this.id = nextId++;
            this.tabelaPaginas = new PageTableEntry[numPaginas];
            for (int i = 0; i < numPaginas; i++) {
                this.tabelaPaginas[i] = new PageTableEntry();
            }
            this.programName = _programName;
            this.imagem = _imagem;
            this.pc = 0; // Ponto de entrada LÓGICO é sempre 0
            this.reg = new int[10]; // Registradores zerados
            Arrays.fill(this.reg, 0);
            this.estado = ProcessState.NEW;
        }
    }

    // ------------------- CLASSE IORequest -------------------
    public class IORequest {
        public PCB pcb;
        public int endLogico;
        public int tipo;

        public IORequest(PCB _pcb, int _endLogico, int _tipo) {
            this.pcb = _pcb;
            this.endLogico = _endLogico;
            this.tipo = _tipo;
        }
    }

    // ------------------- CLASSE IORequestVM -------------------
    public class IORequestVM {
        public PCB pcb;
        public int frameNumber;
        public int pageNumber;
        public int diskAddress;
        public int tipoOperacao; // 0: LOAD_PAGE, 1: SAVE_PAGE

        public IORequestVM(PCB _pcb, int _frameNumber, int _pageNumber, int _diskAddress, int _tipoOperacao) {
            this.pcb = _pcb;
            this.frameNumber = _frameNumber;
            this.pageNumber = _pageNumber;
            this.diskAddress = _diskAddress;
            this.tipoOperacao = _tipoOperacao;
        }
    }

    // ------------------- CLASSE PageFaultPendente -------------------
    public class PageFaultPendente {
        public PCB pcb;
        public int pageNumber;
        public int logicalAddress;

        public PageFaultPendente(PCB _pcb, int _pageNumber, int _logicalAddress) {
            this.pcb = _pcb;
            this.pageNumber = _pageNumber;
            this.logicalAddress = _logicalAddress;
        }
    }

    // ------------------- GERENTE DE PROCESSOS -------------------
    public class GerenteProcessos {
        public LinkedList<PCB> prontos;
        public LinkedList<PCB> bloqueados;
        public PCB rodando;
        private GerenteMemoria gm;
        private HW hw;
        private int tamPg;
        private Utilities utils;
        private SO so;

        public GerenteProcessos(HW _hw, GerenteMemoria _gm, int _tamPg, Utilities _utils, SO _so) {
            this.hw = _hw;
            this.gm = _gm;
            this.tamPg = _tamPg;
            this.utils = _utils;
            this.so = _so;
            this.prontos = new LinkedList<>();
            this.bloqueados = new LinkedList<>();
            this.rodando = null;
        }

        public boolean criaProcesso(Program p) {
            if (p == null || p.image == null) {
                System.out.println("GP: Erro: Programa nulo.");
                return false;
            }
            int nroPalavras = p.image.length;
            int numPaginas = (int) Math.ceil((double) nroPalavras / tamPg);
            
            // Aloca a tabela de páginas (com numPaginas entradas)
            PCB pcb = new PCB(numPaginas, p.name, p.image);
            
            // LAZY LOADING: Aloca e carrega apenas a página 0
            int frame = gm.alocaFrame();
            if (frame == -1) {
                System.out.println("GP: Erro: Memória insuficiente para a página 0 do programa " + p.name);
                return false;
            }
            
            // Inicializa a página 0 como válida
            pcb.tabelaPaginas[0].frameNumber = frame;
            pcb.tabelaPaginas[0].validBit = true;
            pcb.tabelaPaginas[0].dirtyBit = false;
            pcb.tabelaPaginas[0].diskAddress = 0; // Disco começa no endereço 0

            // Carrega a página 0 na memória
            carregarPagina(p.image, pcb, 0);
            
            // As outras páginas são marcadas como inválidas e seus diskAddresses são calculados
            for (int i = 1; i < numPaginas; i++) {
                pcb.tabelaPaginas[i].diskAddress = i * tamPg; // Cada página tem tamPg palavras
            }

            pcb.estado = ProcessState.READY;
            prontos.add(pcb);
            System.out.println("GP: Processo " + pcb.id + " (" + pcb.programName + ") criado com " + numPaginas + " páginas (lazy loading).");
            
            // Se for o primeiro processo, libera o escalonador
            if (rodando == null && prontos.size() == 1) {
                so.semaEscalonador.release();
            }
            return true;
        }

        private void carregarPagina(Word[] programa, PCB pcb, int pagina) {
            int frame = pcb.tabelaPaginas[pagina].frameNumber;
            int endFisicoBase = frame * tamPg;
            int endLogicoBase = pagina * tamPg;

            for (int offset = 0; offset < tamPg; offset++) {
                int endLogico = endLogicoBase + offset;
                int endFisico = endFisicoBase + offset;

                if (endLogico < programa.length) {
                    hw.mem.pos[endFisico] = new Word(programa[endLogico].opc, programa[endLogico].ra, programa[endLogico].rb, programa[endLogico].p);
                } else {
                    // Preenche com zeros se a página não estiver completamente preenchida
                    hw.mem.pos[endFisico] = new Word(Opcode.___, -1, -1, 0);
                }
            }
            System.out.println("GP: Página " + pagina + " do processo " + pcb.id + " carregada no frame " + frame);
        }

        public void desalocaProcesso(int id) {
            PCB pcb = null;

            if (rodando != null && rodando.id == id) {
                pcb = rodando;
                rodando = null;
                System.out.println("GP: Desalocando processo rodando " + id);
            } 
            else {
                Iterator<PCB> iterator = prontos.iterator();
                while (iterator.hasNext()) {
                    PCB p = iterator.next();
                    if (p.id == id) {
                        pcb = p;
                        iterator.remove();
                        System.out.println("GP: Desalocando processo da fila " + id);
                        break;
                    }
                }
                
                if (pcb == null) {
                    iterator = bloqueados.iterator();
                    while (iterator.hasNext()) {
                        PCB p = iterator.next();
                        if (p.id == id) {
                            pcb = p;
                            iterator.remove();
                            System.out.println("GP: Desalocando processo bloqueado " + id);
                            break;
                        }
                    }
                }
            }
            
            if (pcb == null) {
                System.out.println("GP: Processo " + id + " não encontrado.");
                return;
            }
            
            // Desaloca todas as páginas do processo
            for (PageTableEntry entry : pcb.tabelaPaginas) {
                if (entry.validBit) {
                    gm.liberaFrame(entry.frameNumber);
                }
            }
            
            pcb.estado = ProcessState.TERMINATED;
            System.out.println("GP: Processo " + id + " desalocado.");
        }

        public PCB findAndRemoveFromBlocked(int pid) {
            Iterator<PCB> iterator = bloqueados.iterator();
            while (iterator.hasNext()) {
                PCB p = iterator.next();
                if (p.id == pid) {
                    iterator.remove();
                    return p;
                }
            }
            return null;
        }

        public void ps() {
            System.out.println("=== LISTA DE PROCESSOS ===");
            System.out.println("ID\tEstado\t\tPrograma");
            if (rodando != null) {
                System.out.println(rodando.id + "\tRUNNING\t\t" + rodando.programName);
            }
            for (PCB p : prontos) {
                System.out.println(p.id + "\tREADY\t\t" + p.programName);
            }
            for (PCB p : bloqueados) {
                System.out.println(p.id + "\tBLOCKED\t\t" + p.programName);
            }
            if (rodando == null && prontos.isEmpty() && bloqueados.isEmpty()) {
                System.out.println("Nenhum processo ativo.");
            }
        }

        public void dump(int id) {
            PCB pcb = null;
            if (rodando != null && rodando.id == id) {
                pcb = rodando;
            } else {
                for (PCB p : prontos) {
                    if (p.id == id) {
                        pcb = p;
                        break;
                    }
                }
                if (pcb == null) {
                    for (PCB p : bloqueados) {
                        if (p.id == id) {
                            pcb = p;
                            break;
                        }
                    }
                }
            }
            if (pcb == null) {
                System.out.println("GP: Processo " + id + " não encontrado.");
                return;
            }
            System.out.println("=== DUMP DO PROCESSO " + id + " (" + pcb.programName + ") ===");
            System.out.println("PC: " + pcb.pc);
            System.out.println("Estado: " + pcb.estado);
            System.out.println("Registradores:");
            for (int i = 0; i < pcb.reg.length; i++) {
                System.out.println("  r[" + i + "]: " + pcb.reg[i]);
            }
            System.out.println("Tabela de Páginas:");
            for (int i = 0; i < pcb.tabelaPaginas.length; i++) {
                PageTableEntry entry = pcb.tabelaPaginas[i];
                System.out.println("  Página " + i + " -> Frame " + entry.frameNumber + 
                                 " [V:" + entry.validBit + " D:" + entry.dirtyBit + 
                                 " Disk:" + entry.diskAddress + "]");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------
    // --------------------- H A R D W A R E - definicoes de HW
    // ----------------------------------------------

    public class Memory {
        public Word[] pos; // pos[i] é a posição i da memória. cada posição é uma palavra.

        public Memory(int size) {
            pos = new Word[size];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = new Word(Opcode.___, -1, -1, -1);
            }
        }
    }

    public class Word {
        public Opcode opc;
        public int ra;
        public int rb;
        public int p;

        public Word(Opcode _opc, int _ra, int _rb, int _p) {
            opc = _opc;
            ra = _ra;
            rb = _rb;
            p  = _p;
        }
    }

    // -------------------------------------------------------------------------------------------------------
    // --------------------- C P U - definicoes da CPU
    // -----------------------------------------------------

    public enum Opcode {
        DATA, ___, JMP, JMPI, JMPIG, JMPIL, JMPIE, JMPIM, JMPIGM, JMPILM, JMPIEM,
        JMPIGK, JMPILK, JMPIEK, JMPIGT, ADDI, SUBI, ADD, SUB, MULT,
        LDI, LDD, STD, LDX, STX, MOVE, SYSCALL, STOP
    }

    public enum Interrupts {
        noInterrupt, intEnderecoInvalido, intInstrucaoInvalida, intOverflow, 
        intFimDeFatiaDeTempo, intIO, intPageFault, intFimCargaDiscoVM, intFimSalvaDiscoVM;
    }

    // ------------------- GERENTE DE MEMÓRIA COM PAGINAÇÃO E VITIMIZAÇÃO -------------------
    public class GerenteMemoria {
        private boolean[] framesOcupados;
        private int numFramesLivres;
        private int tamPg;
        private LinkedList<Integer> fifoQueue; // Para política FIFO de substituição
        private Map<Integer, Pair<PCB, Integer>> frameToPageMap; // Mapeia frame -> (PCB, pageNumber)

        public GerenteMemoria(int tamMem, int tamPg) {
            int numFrames = tamMem / tamPg;
            this.tamPg = tamPg;
            this.framesOcupados = new boolean[numFrames];
            Arrays.fill(this.framesOcupados, false);
            this.numFramesLivres = numFrames;
            this.fifoQueue = new LinkedList<>();
            this.frameToPageMap = new HashMap<>();
            
            // Inicializa a fila FIFO com todos os frames
            for (int i = 0; i < numFrames; i++) {
                fifoQueue.add(i);
            }
        }

        public int alocaFrame() {
            if (numFramesLivres > 0) {
                for (int i = 0; i < framesOcupados.length; i++) {
                    if (!framesOcupados[i]) {
                        framesOcupados[i] = true;
                        numFramesLivres--;
                        fifoQueue.remove((Integer)i); // Remove da posição atual
                        fifoQueue.add(i); // Coloca no final (mais recente)
                        return i;
                    }
                }
            }
            return -1; // Sem frames livres
        }

        public void liberaFrame(int frame) {
            if (frame >= 0 && frame < framesOcupados.length && framesOcupados[frame]) {
                framesOcupados[frame] = false;
                numFramesLivres++;
                frameToPageMap.remove(frame);
                // Não precisa remover/adicionar na fifoQueue pois será recolocado quando realocado
            }
        }

        public void ocupaFrame(int frame, PCB pcb, int pageNumber) {
            if (frame >= 0 && frame < framesOcupados.length) {
                framesOcupados[frame] = true;
                numFramesLivres--;
                frameToPageMap.put(frame, new Pair<>(pcb, pageNumber));
                fifoQueue.remove((Integer)frame);
                fifoQueue.add(frame);
            }
        }

        public int escolheVitima() {
            if (fifoQueue.isEmpty()) return -1;
            return fifoQueue.pollFirst(); // Remove e retorna o mais antigo (FIFO)
        }

        public Pair<PCB, Integer> findPageByFrame(int frame) {
            return frameToPageMap.get(frame);
        }

        public int getNumFramesLivres() {
            return numFramesLivres;
        }
    }

    // ------------------- CLASSE PAIR PARA MAPEAMENTO -------------------
    public static class Pair<K, V> {
        private K key;
        private V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() { return key; }
        public V getValue() { return value; }
    }

    public class CPU {
        private int maxInt;
        private int minInt;
        private int pc;
        private Word ir;
        private int[] reg;
        private Interrupts irpt;
        private Word[] m;
        private InterruptHandling ih;
        private SysCallHandling sysCall;
        private boolean cpuStop;
        private boolean debug;
        private Utilities u;
        private PageTableEntry[] regTabelaPaginas;
        private int tamPg;
        private int instrucoesExecutadas = 0;
        private final int DELTA_INSTRUCOES = 4;

        // Mecanismo para interrupção de E/S
        private volatile boolean ioInterruptPending = false;
        private volatile int ioProcessId = -1;

        // Mecanismo para interrupção de disco VM
        private volatile boolean ioVMInterruptPending = false;
        private volatile int ioVMProcessId = -1;
        private volatile int ioVMTipo = -1;

        // Para page faults
        private int pageFaultLogicalAddress = -1;

        public CPU(Memory _mem, boolean _debug, int _tamPg) {
            maxInt = 32767;
            minInt = -32767;
            m = _mem.pos;
            reg = new int[10];
            debug = _debug;
            tamPg = _tamPg;
            regTabelaPaginas = null;
        }

        public void setDebug(boolean _debug) {
            debug = _debug;
        }

        public void setTabelaPaginas(PageTableEntry[] tabela) {
            this.regTabelaPaginas = tabela;
        }

        public void setContext(int _pc, int[] _reg, PageTableEntry[] _tabelaPaginas) {
            this.pc = _pc;
            this.reg = _reg;
            this.regTabelaPaginas = _tabelaPaginas;
            this.irpt = Interrupts.noInterrupt;
            this.instrucoesExecutadas = 0;
        }

        public int getPc() {
            return pc;
        }

        public int[] getReg() {
            return reg;
        }

        public synchronized void setIOInterrupt(int pid) {
            ioInterruptPending = true;
            ioProcessId = pid;
        }

        public int getPendingIOProcessId() {
            return ioProcessId;
        }

        public synchronized void setIOInterruptVM(int pid, int tipo) {
            ioVMInterruptPending = true;
            ioVMProcessId = pid;
            ioVMTipo = tipo;
        }

        public int getPageFaultLogicalAddress() {
            return pageFaultLogicalAddress;
        }

        private int translate(int logicalAddress) {
            // Se não há paginação ativa, retorna endereço direto
            if (regTabelaPaginas == null) {
                return logicalAddress;
            }
            
            int pageNumber = logicalAddress / tamPg;
            int offset = logicalAddress % tamPg;
            
            // Verifica se a página é válida
            if (pageNumber < 0 || pageNumber >= regTabelaPaginas.length) {
                irpt = Interrupts.intEnderecoInvalido;
                return -1;
            }
            
            PageTableEntry entry = regTabelaPaginas[pageNumber];
            if (!entry.validBit) {
                // PAGE FAULT: página não está na memória
                pageFaultLogicalAddress = logicalAddress;
                irpt = Interrupts.intPageFault;
                return -1;
            }
            
            // Physical Address Calculation
            int physicalAddress = (entry.frameNumber * tamPg) + offset;
            return physicalAddress;
        }

        public void setAddressOfHandlers(InterruptHandling _ih, SysCallHandling _sysCall) {
            ih = _ih;
            sysCall = _sysCall;
        }

        public void setUtilities(Utilities _u) {
            u = _u;
        }

        private boolean legal(int e) {
            if (e >= 0 && e < m.length) {
                return true;
            } else {
                irpt = Interrupts.intEnderecoInvalido;
                return false;
            }
        }

        private boolean testOverflow(int v) {
            if ((v < minInt) || (v > maxInt)) {
                irpt = Interrupts.intOverflow;
                return false;
            }
            return true;
        }

        // Método auxiliar para marcar dirty bit
        private void markPageDirty(int logicalAddress) {
            if (regTabelaPaginas != null) {
                int pageNumber = logicalAddress / tamPg;
                if (pageNumber >= 0 && pageNumber < regTabelaPaginas.length) {
                    PageTableEntry entry = regTabelaPaginas[pageNumber];
                    if (entry.validBit) {
                        entry.dirtyBit = true;
                    }
                }
            }
        }

        public void run_one_instruction() {
            cpuStop = false;
            
            // Verifica interrupção de E/S pendente
            if (ioInterruptPending) {
                irpt = Interrupts.intIO;
                ioInterruptPending = false;
            }
            
            // Verifica interrupção de disco VM
            if (ioVMInterruptPending) {
                if (ioVMTipo == 0) {
                    irpt = Interrupts.intFimCargaDiscoVM;
                } else if (ioVMTipo == 1) {
                    irpt = Interrupts.intFimSalvaDiscoVM;
                }
                ioVMInterruptPending = false;
            }
            
            // --------------------------------------------------------------------------------------------------
            // FASE DE FETCH
            int physPC = translate(pc);
            if (legal(physPC)) {
                ir = m[physPC];
                
                if (debug) {
                    System.out.print("                                                         regs: ");
                    for (int i = 0; i < 10; i++) {
                        System.out.print(" r[" + i + "]:" + reg[i]);
                    }
                    System.out.println();
                }
                if (debug) {
                    System.out.print("                         pc: " + pc + "       exec: ");
                    u.dump(ir);
                }

            // --------------------------------------------------------------------------------------------------
            // FASE DE EXECUCAO DA INSTRUCAO CARREGADA NO ir
                switch (ir.opc) {
                    case LDI:
                        reg[ir.ra] = ir.p;
                        pc++;
                        break;
                    case LDD:
                        int physAddrLDD = translate(ir.p);
                        if (legal(physAddrLDD)) {
                            reg[ir.ra] = m[physAddrLDD].p;
                            pc++;
                        }
                        break;
                    case LDX:
                        int logicalAddrLDX = reg[ir.rb];
                        int physAddrLDX = translate(logicalAddrLDX);
                        if (legal(physAddrLDX)) {
                            reg[ir.ra] = m[physAddrLDX].p;
                            pc++;
                        }
                        break;
                    case STD:
                        int physAddrSTD = translate(ir.p);
                        if (legal(physAddrSTD)) {
                            m[physAddrSTD].opc = Opcode.DATA;
                            m[physAddrSTD].p = reg[ir.ra];
                            // MARCA DIRTY BIT
                            markPageDirty(ir.p);
                            pc++;
                            if (debug) { 
                                System.out.print("                                                 ");   
                                u.dump(physAddrSTD,physAddrSTD+1);                             
                            }
                        }
                        break;
                    case STX:
                        int logicalAddrSTX = reg[ir.ra];
                        int physAddrSTX = translate(logicalAddrSTX);
                        if (legal(physAddrSTX)) {
                            m[physAddrSTX].opc = Opcode.DATA;
                            m[physAddrSTX].p = reg[ir.rb];
                            // MARCA DIRTY BIT
                            markPageDirty(logicalAddrSTX);
                            pc++;
                        }
                        break;
                    case MOVE:
                        reg[ir.ra] = reg[ir.rb];
                        pc++;
                        break;
                    case ADD:
                        reg[ir.ra] = reg[ir.ra] + reg[ir.rb];
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case ADDI:
                        reg[ir.ra] = reg[ir.ra] + ir.p;
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case SUB:
                        reg[ir.ra] = reg[ir.ra] - reg[ir.rb];
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case SUBI:
                        reg[ir.ra] = reg[ir.ra] - ir.p;
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case MULT:
                        reg[ir.ra] = reg[ir.ra] * reg[ir.rb];
                        testOverflow(reg[ir.ra]);
                        pc++;
                        break;
                    case JMP:
                        pc = ir.p;
                        break;
                    case JMPIM:
                        int physAddrJMPIM = translate(ir.p);
                        if (legal(physAddrJMPIM)) {
                            pc = m[physAddrJMPIM].p;
                        }
                        break;
                    case JMPIG:
                        if (reg[ir.rb] > 0) {
                            pc = reg[ir.ra];
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIGK:
                        if (reg[ir.rb] > 0) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case JMPILK:
                        if (reg[ir.rb] < 0) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIEK:
                        if (reg[ir.rb] == 0) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIL:
                        if (reg[ir.rb] < 0) {
                            pc = reg[ir.ra];
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIE:
                        if (reg[ir.rb] == 0) {
                            pc = reg[ir.ra];
                        } else {
                            pc++;
                        }
                        break;
                    case JMPIGM:
                        int physAddrJMPIGM = translate(ir.p);
                        if (legal(physAddrJMPIGM)){
                            if (reg[ir.rb] > 0) {
                               pc = m[physAddrJMPIGM].p;
                            } else {
                                pc++;
                           }
                        }
                        break;
                    case JMPILM:
                        int physAddrJMPILM = translate(ir.p);
                        if (legal(physAddrJMPILM)) {
                            if (reg[ir.rb] < 0) {
                                pc = m[physAddrJMPILM].p;
                            } else {
                                pc++;
                            }
                        }
                        break;
                    case JMPIEM:
                        int physAddrJMPIEM = translate(ir.p);
                        if (legal(physAddrJMPIEM)) {
                            if (reg[ir.rb] == 0) {
                                pc = m[physAddrJMPIEM].p;
                            } else {
                                pc++;
                            }
                        }
                        break;
                    case JMPIGT:
                        if (reg[ir.ra] > reg[ir.rb]) {
                            pc = ir.p;
                        } else {
                            pc++;
                        }
                        break;
                    case DATA:
                        irpt = Interrupts.intInstrucaoInvalida;
                        break;
                    case SYSCALL:
                        sysCall.handle();
                        pc++;
                        break;
                    case STOP:
                        sysCall.stop();
                        cpuStop = true;
                        break;
                    default:
                        irpt = Interrupts.intInstrucaoInvalida;
                        break;
                }
            }
            
            // Controle do Quantum - Preempção
            instrucoesExecutadas++;
            if (instrucoesExecutadas >= DELTA_INSTRUCOES) {
                irpt = Interrupts.intFimDeFatiaDeTempo;
            }
            
            // --------------------------------------------------------------------------------------------------
            // VERIFICA INTERRUPÇÃO
            if (irpt != Interrupts.noInterrupt) {
                ih.handle(irpt);
                cpuStop = true;
            }
        }
    }

    // ------------------ C P U - fim
    // -----------------------------------------------------------------------

    // ------------------- HW - constituido de CPU e MEMORIA
    // -----------------------------------------------
    public class HW {
        public Memory mem;
        public CPU cpu;
        public int tamPg;

        public HW(int tamMem, int _tamPg) {
            mem = new Memory(tamMem);
            tamPg = _tamPg;
            cpu = new CPU(mem, true, _tamPg);
        }
    }

    // --------------------H A R D W A R E - fim
    // -------------------------------------------------------------

    // ------------------- THREADS DO SISTEMA -------------------

    public class ThreadEscalonador implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    so.semaEscalonador.acquire();
                    PCB proximo = so.gp.prontos.pollFirst();
                    if (proximo != null) {
                        proximo.estado = ProcessState.RUNNING;
                        so.gp.rodando = proximo;
                        hw.cpu.setContext(proximo.pc, proximo.reg, proximo.tabelaPaginas);
                        so.semaCPU.release();
                    } else {
                        so.gp.rodando = null;
                    }
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    public class ThreadCPU implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    so.semaCPU.acquire();
                    while (so.gp.rodando != null) {
                        hw.cpu.run_one_instruction();
                    }
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    public class ThreadConsole implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    IORequest req = so.filaPedidosConsole.take();
                    PCB pcb = req.pcb;
                    int endFisico = hw.cpu.translate(req.endLogico);
                    if (req.tipo == 1) {
                        System.out.print("    > CONSOLE INPUT (para pid: " + pcb.id + ", end: " + req.endLogico + "): ");
                        Scanner s = new Scanner(System.in);
                        int valor = s.nextInt();
                        hw.mem.pos[endFisico].p = valor;
                        hw.mem.pos[endFisico].opc = Opcode.DATA;
                    } else if (req.tipo == 2) {
                        System.out.println("    > CONSOLE OUTPUT (de pid: " + pcb.id + ", end: " + req.endLogico + "): " + hw.mem.pos[endFisico].p);
                    }
                    hw.cpu.setIOInterrupt(pcb.id);
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    // ------------------- THREAD DISCO VM -------------------
    public class ThreadDiscoVM implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    IORequestVM req = so.filaPedidosDiscoVM.take();
                    
                    // Simula tempo de I/O do disco
                    Thread.sleep(100);
                    
                    if (req.tipoOperacao == 0) {
                        // LOAD_PAGE: Carrega página do disco para memória
                        System.out.println("DISCO VM: Carregando página " + req.pageNumber + " do processo " + req.pcb.id + " para frame " + req.frameNumber);
                        
                        // Carrega a página da imagem do programa (simulando disco)
                        Word[] imagem = req.pcb.imagem;
                        int endLogicoBase = req.pageNumber * hw.tamPg;
                        for (int offset = 0; offset < hw.tamPg; offset++) {
                            int endLogico = endLogicoBase + offset;
                            int endFisico = (req.frameNumber * hw.tamPg) + offset;
                            if (endLogico < imagem.length) {
                                hw.mem.pos[endFisico] = new Word(imagem[endLogico].opc, imagem[endLogico].ra, imagem[endLogico].rb, imagem[endLogico].p);
                            } else {
                                hw.mem.pos[endFisico] = new Word(Opcode.___, -1, -1, 0);
                            }
                        }
                        so.ultimoIOVMConcluido = req;
                        hw.cpu.setIOInterruptVM(req.pcb.id, 0);
                    } else if (req.tipoOperacao == 1) {
                        // SAVE_PAGE: Salva página da memória para disco
                        System.out.println("DISCO VM: Salvando página " + req.pageNumber + " do processo " + req.pcb.id + " do frame " + req.frameNumber);
                        // Em um sistema real, aqui salvaríamos a página no disco
                        // Para simulação, apenas marcamos que foi salva
                        so.ultimoIOVMConcluido = req;
                        hw.cpu.setIOInterruptVM(req.pcb.id, 1);
                    }
                } catch (InterruptedException e) { e.printStackTrace(); }
            }
        }
    }

    // ------------------- I N T E R R U P C O E S - rotinas de tratamento
    // ----------------------------------
    public class InterruptHandling {
        private SO so;

        public InterruptHandling(SO _so) {
            so = _so;
        }

        public void handle(Interrupts irpt) {
            switch (irpt) {
                case intFimDeFatiaDeTempo:
                    PCB processoAtual = so.gp.rodando;
                    processoAtual.pc = hw.cpu.getPc();
                    processoAtual.estado = ProcessState.READY;
                    so.gp.prontos.addLast(processoAtual);
                    so.gp.rodando = null;
                    so.semaEscalonador.release();
                    break;
                    
                case intIO:
                    int pid = hw.cpu.getPendingIOProcessId();
                    PCB pcb = so.gp.findAndRemoveFromBlocked(pid);
                    if (pcb != null) {
                        pcb.estado = ProcessState.READY;
                        so.gp.prontos.addLast(pcb);
                        System.out.println("    > INT IO: Processo " + pcb.id + " desbloqueado -> READY.");
                    }
                    break;
                    
                case intPageFault:
                    handlePageFault();
                    break;
                    
                case intFimCargaDiscoVM:
                    handleFimCargaDiscoVM();
                    break;
                    
                case intFimSalvaDiscoVM:
                    handleFimSalvaDiscoVM();
                    break;
                    
                case intEnderecoInvalido:
                case intInstrucaoInvalida:
                case intOverflow:
                    System.out.println("!!!! INTERRUPÇÃO FATAL: " + irpt + " no Processo " + so.gp.rodando.id);
                    PCB processoComErro = so.gp.rodando;
                    so.gp.rodando = null;
                    so.gp.desalocaProcesso(processoComErro.id);
                    so.semaEscalonador.release();
                    break;
                    
                default:
                    System.out.println("IH: Interrupção não tratada: " + irpt);
                    break;
            }
        }

        private void handlePageFault() {
            PCB processoAtual = so.gp.rodando;
            int logicalAddress = hw.cpu.getPageFaultLogicalAddress();
            int pageNumber = logicalAddress / hw.tamPg;
            
            System.out.println("    > PAGE FAULT: Processo " + processoAtual.id + " na página " + pageNumber + " (end. lógico: " + logicalAddress + ")");

            // Salva contexto e bloqueia processo
            processoAtual.pc = hw.cpu.getPc();
            processoAtual.estado = ProcessState.BLOCKED;
            so.gp.bloqueados.add(processoAtual);
            so.gp.rodando = null;

            // Tenta alocar um frame livre
            int frameLivre = so.gerenteMem.alocaFrame();
            if (frameLivre != -1) {
                // Cenário 1: Frame livre encontrado
                System.out.println("    > PAGE FAULT: Frame livre " + frameLivre + " alocado");
                processoAtual.tabelaPaginas[pageNumber].frameNumber = frameLivre;
                // Marca o frame como ocupado por esta página
                so.gerenteMem.ocupaFrame(frameLivre, processoAtual, pageNumber);
                
                // Solicita carga da página do disco
                IORequestVM req = new IORequestVM(processoAtual, frameLivre, pageNumber, 
                                                pageNumber * hw.tamPg, 0);
                so.filaPedidosDiscoVM.add(req);
                so.semaDiscoVM.release();
            } else {
                // Cenário 2: Sem frames livres - vitimização
                System.out.println("    > PAGE FAULT: Memória cheia, iniciando vitimização");
                int frameVitima = so.gerenteMem.escolheVitima();
                
                if (frameVitima != -1) {
                    Pair<PCB, Integer> vitima = so.gerenteMem.findPageByFrame(frameVitima);
                    if (vitima != null) {
                        PCB pcbVitima = vitima.getKey();
                        int pageVitima = vitima.getValue();
                        PageTableEntry entryVitima = pcbVitima.tabelaPaginas[pageVitima];
                        
                        if (entryVitima.dirtyBit) {
                            // Vítima dirty - precisa salvar no disco
                            System.out.println("    > VITIMIZAÇÃO: Frame " + frameVitima + " (página " + pageVitima + " do processo " + pcbVitima.id + ") é dirty, salvando...");
                            IORequestVM req = new IORequestVM(pcbVitima, frameVitima, pageVitima, 
                                                            entryVitima.diskAddress, 1);
                            so.filaPedidosDiscoVM.add(req);
                            so.semaDiscoVM.release();
                            
                            // Armazena page fault pendente
                            so.filaPageFaultPendentes.add(new PageFaultPendente(processoAtual, pageNumber, logicalAddress));
                        } else {
                            // Vítima clean - pode reusar imediatamente
                            System.out.println("    > VITIMIZAÇÃO: Frame " + frameVitima + " (página " + pageVitima + " do processo " + pcbVitima.id + ") é clean, reutilizando...");
                            entryVitima.validBit = false;
                            processoAtual.tabelaPaginas[pageNumber].frameNumber = frameVitima;
                            so.gerenteMem.ocupaFrame(frameVitima, processoAtual, pageNumber);
                            
                            // Solicita carga da página
                            IORequestVM req = new IORequestVM(processoAtual, frameVitima, pageNumber, 
                                                            pageNumber * hw.tamPg, 0);
                            so.filaPedidosDiscoVM.add(req);
                            so.semaDiscoVM.release();
                        }
                    }
                }
            }
            
            so.semaEscalonador.release();
        }

        private void handleFimCargaDiscoVM() {
            IORequestVM req = so.ultimoIOVMConcluido;
            if (req != null) {
                // Atualiza tabela de páginas
                PageTableEntry entry = req.pcb.tabelaPaginas[req.pageNumber];
                entry.validBit = true;
                entry.dirtyBit = false;
                
                // Move processo para ready
                so.gp.bloqueados.remove(req.pcb);
                req.pcb.estado = ProcessState.READY;
                so.gp.prontos.add(req.pcb);
                
                System.out.println("    > FIM CARGA DISCO: Página " + req.pageNumber + " do processo " + req.pcb.id + " carregada no frame " + req.frameNumber);
            }
        }

        private void handleFimSalvaDiscoVM() {
            IORequestVM req = so.ultimoIOVMConcluido;
            if (req != null) {
                // Libera frame da vítima
                so.gerenteMem.liberaFrame(req.frameNumber);
                req.pcb.tabelaPaginas[req.pageNumber].validBit = false;
                
                System.out.println("    > FIM SALVA DISCO: Página " + req.pageNumber + " do processo " + req.pcb.id + " salva, frame " + req.frameNumber + " liberado");
                
                // Processa page fault pendente
                PageFaultPendente pendente = so.filaPageFaultPendentes.poll();
                if (pendente != null) {
                    System.out.println("    > Processando page fault pendente do processo " + pendente.pcb.id + " página " + pendente.pageNumber);
                    
                    // Aloca frame para o page fault pendente
                    pendente.pcb.tabelaPaginas[pendente.pageNumber].frameNumber = req.frameNumber;
                    so.gerenteMem.ocupaFrame(req.frameNumber, pendente.pcb, pendente.pageNumber);
                    
                    // Solicita carga da página
                    IORequestVM newReq = new IORequestVM(pendente.pcb, req.frameNumber, pendente.pageNumber, 
                                                       pendente.pageNumber * hw.tamPg, 0);
                    so.filaPedidosDiscoVM.add(newReq);
                    so.semaDiscoVM.release();
                }
            }
        }
    }

    // ------------------- C H A M A D A S D E S I S T E M A - rotinas de tratamento
    // ----------------------
    public class SysCallHandling {
        private HW hw;
        private SO so;

        public SysCallHandling(HW _hw, SO _so) {
            hw = _hw;
            so = _so;
        }

        public void stop() {
            System.out.println("    > SYSCALL STOP: Processo " + so.gp.rodando.id + " terminado.");
            PCB processoTerminado = so.gp.rodando;
            so.gp.rodando = null;
            so.gp.desalocaProcesso(processoTerminado.id);
            so.semaEscalonador.release();
        }

        public void handle() {
            PCB processoAtual = so.gp.rodando;
            processoAtual.pc = hw.cpu.getPc();
            int tipoChamada = hw.cpu.reg[8];
            if (tipoChamada == 1 || tipoChamada == 2) {
                System.out.println("    > TRAP: Processo " + processoAtual.id + " solicitou E/S.");
                processoAtual.estado = ProcessState.BLOCKED;
                so.gp.bloqueados.add(processoAtual);
                so.gp.rodando = null;
                int endLogico = hw.cpu.reg[9];
                so.filaPedidosConsole.add(new IORequest(processoAtual, endLogico, tipoChamada));
                so.semaConsole.release();
                so.semaEscalonador.release();
            } else {
                System.out.println("    > TRAP: Chamada de sistema inválida: " + tipoChamada);
                so.ih.handle(Interrupts.intInstrucaoInvalida);
            }       
        }
    }

    // ------------------ U T I L I T A R I O S D O S I S T E M A
    // -----------------------------------------
    public class Utilities {
        private HW hw;
        private GerenteMemoria gm;

        public Utilities(HW _hw, GerenteMemoria _gm) {
            hw = _hw;
            gm = _gm;
        }

        public void dump(Word w) {
            System.out.print("[ ");
            System.out.print(w.opc);
            System.out.print(", ");
            System.out.print(w.ra);
            System.out.print(", ");
            System.out.print(w.rb);
            System.out.print(", ");
            System.out.print(w.p);
            System.out.println("  ] ");
        }

        public void dump(int ini, int fim) {
            Word[] m = hw.mem.pos;
            for (int i = ini; i < fim; i++) {
                System.out.print(i);
                System.out.print(":  ");
                dump(m[i]);
            }
        }
    }

    public class SO {
        public InterruptHandling ih;
        public SysCallHandling sc;
        public Utilities utils;
        public GerenteProcessos gp;
        public HW hw;
        public GerenteMemoria gerenteMem;

        // Estruturas de sincronização
        public Semaphore semaCPU = new Semaphore(0);
        public Semaphore semaEscalonador = new Semaphore(0);
        public Semaphore semaConsole = new Semaphore(0);
        public Semaphore semaDiscoVM = new Semaphore(0);
        
        // Filas
        public LinkedBlockingQueue<IORequest> filaPedidosConsole = new LinkedBlockingQueue<>();
        public LinkedBlockingQueue<IORequestVM> filaPedidosDiscoVM = new LinkedBlockingQueue<>();
        public Queue<PageFaultPendente> filaPageFaultPendentes = new LinkedList<>();
        
        // Threads
        public ThreadEscalonador escalonador;
        public ThreadCPU cpuThread;
        public ThreadConsole console;
        public ThreadDiscoVM discoVM;
        
        // Para comunicação com handlers de disco VM
        public IORequestVM ultimoIOVMConcluido;
        public SO(HW hw, GerenteMemoria gm, int tamPg) {
            this.hw = hw;
            this.gerenteMem = gm;
            utils = new Utilities(hw, gm);
            gp = new GerenteProcessos(hw, gm, tamPg, utils, this);
            ih = new InterruptHandling(this);
            sc = new SysCallHandling(hw, this);
            hw.cpu.setAddressOfHandlers(ih, sc);
            
            escalonador = new ThreadEscalonador();
            cpuThread = new ThreadCPU();
            console = new ThreadConsole();
            discoVM = new ThreadDiscoVM();
        }
    }

    // ------------------- S I S T E M A
    // --------------------------------------------------------------------

    public HW hw;
    public SO so;
    public Programs progs;
    public GerenteMemoria gerenteMem;
    private int tamPagina;

    public Sistema(int tamMem, int _tamPagina) {
        this.tamPagina = _tamPagina;
        hw = new HW(tamMem, _tamPagina);
        gerenteMem = new GerenteMemoria(tamMem, _tamPagina);
        so = new SO(hw, gerenteMem, _tamPagina);
        hw.cpu.setUtilities(so.utils);
        progs = new Programs();
    }

    // CLI interativa
    public void runCLI() {
        System.out.println("Sistema Operacional com Memória Virtual iniciado.");
        System.out.println("Digite 'help' para comandos.");
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            
            if (line.isEmpty()) {
                continue;
            }
            
            String[] args = line.split("\\s+");
            String cmd = args[0].toLowerCase();
            
            try {
                switch (cmd) {
                    case "new":
                        if (args.length < 2) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        Program p = progs.retrieveProgram(args[1]);
                        if (p == null) {
                            System.out.println("Erro: Programa '" + args[1] + "' não encontrado.");
                        } else {
                            boolean sucesso = so.gp.criaProcesso(p);
                            if (!sucesso) {
                                System.out.println("Erro: Falha ao criar processo (sem memória).");
                            }
                        }
                        break;
                        
                    case "rm":
                        if (args.length < 2) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        int id = Integer.parseInt(args[1]);
                        so.gp.desalocaProcesso(id);
                        break;
                        
                    case "ps":
                        so.gp.ps();
                        break;
                        
                    case "dump":
                        if (args.length < 2) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        int dumpId = Integer.parseInt(args[1]);
                        so.gp.dump(dumpId);
                        break;
                        
                    case "dumpm":
                        if (args.length < 3) {
                            throw new ArrayIndexOutOfBoundsException();
                        }
                        int inicio = Integer.parseInt(args[1]);
                        int fim = Integer.parseInt(args[2]);
                        so.utils.dump(inicio, fim);
                        break;
                        
                    case "traceon":
                        hw.cpu.setDebug(true);
                        System.out.println("CPU trace ligado.");
                        break;
                        
                    case "traceoff":
                        hw.cpu.setDebug(false);
                        System.out.println("CPU trace desligado.");
                        break;
                        
                    case "meminfo":
                        System.out.println("=== INFORMAÇÕES DE MEMÓRIA ===");
                        System.out.println("Frames livres: " + gerenteMem.getNumFramesLivres());
                        System.out.println("Tamanho da página: " + tamPagina);
                        break;
                        
                    case "help":
                        System.out.println("=== COMANDOS DISPONÍVEIS ===");
                        System.out.println("new [prog]    - Cria novo processo com programa 'prog'");
                        System.out.println("rm [id]       - Remove processo com ID 'id'");
                        System.out.println("ps            - Lista todos os processos");
                        System.out.println("dump [id]     - Mostra detalhes do processo 'id'");
                        System.out.println("dumpm [ini] [fim] - Dump da memória física de 'ini' a 'fim'");
                        System.out.println("meminfo       - Mostra informações de memória");
                        System.out.println("traceon       - Liga trace da CPU");
                        System.out.println("traceoff      - Desliga trace da CPU");
                        System.out.println("exit          - Encerra o sistema");
                        System.out.println("help          - Mostra esta ajuda");
                        break;
                        
                    case "exit":
                        System.out.println("Encerrando sistema...");
                        scanner.close();
                        System.exit(0);
                        break;
                        
                    default:
                        System.out.println("Erro: Comando '" + cmd + "' não reconhecido. Digite 'help'.");
                        break;
                }
            } catch (NumberFormatException e) {
                System.out.println("Erro: Argumento inválido. Esperava um número.");
            } catch (ArrayIndexOutOfBoundsException e) {
                System.out.println("Erro: Faltam argumentos para o comando '" + cmd + "'.");
            } catch (Exception e) {
                System.out.println("Erro inesperado: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ------------------- instancia e testa sistema
    public static void main(String args[]) {
        Sistema s = new Sistema(1024, 16);  // 1024 palavras, página de 16 palavras
        
        // Inicia threads do sistema
        Thread threadEscalonador = new Thread(s.so.escalonador);
        Thread threadCPU = new Thread(s.so.cpuThread);
        Thread threadConsole = new Thread(s.so.console);
        Thread threadDiscoVM = new Thread(s.so.discoVM);
        
        threadEscalonador.start();
        threadCPU.start();
        threadConsole.start();
        threadDiscoVM.start();
        
        // Executa CLI no thread principal
        s.runCLI();
    }

    // --------------- P R O G R A M A S - não fazem parte do sistema
    public class Program {
        public String name;
        public Word[] image;

        public Program(String n, Word[] i) {
            name = n;
            image = i;
        }
    }

    public class Programs {
        public Program retrieveProgram(String pname) {
            for (Program p : progs) {
                if (p != null && p.name.equals(pname))
                    return p;
            }
            return null;
        }

        public Program[] progs = {
                new Program("fatorial",
                        new Word[] {
                                // este fatorial so aceita valores positivos. nao pode ser zero
                                // linha coment
                                new Word(Opcode.LDI, 0, -1, 7), // 0 r0 é valor a calcular fatorial
                                new Word(Opcode.LDI, 1, -1, 1), // 1 r1 é 1 para multiplicar (por r0)
                                new Word(Opcode.LDI, 6, -1, 1), // 2 r6 é 1 o decremento
                                new Word(Opcode.LDI, 7, -1, 8), // 3 r7 tem posicao 8 para fim do programa
                                new Word(Opcode.JMPIE, 7, 0, 0), // 4 se r0=0 pula para r7(=8)
                                new Word(Opcode.MULT, 1, 0, -1), // 5 r1 = r1 * r0 (r1 acumula o produto por cada termo)
                                new Word(Opcode.SUB, 0, 6, -1), // 6 r0 = r0 - r6 (r6=1) decrementa r0 para proximo
                                                                // termo
                                new Word(Opcode.JMP, -1, -1, 4), // 7 vai p posicao 4
                                new Word(Opcode.STD, 1, -1, 10), // 8 coloca valor de r1 na posição 10
                                new Word(Opcode.STOP, -1, -1, -1), // 9 stop
                                new Word(Opcode.DATA, -1, -1, -1) // 10 ao final o valor está na posição 10 da memória
                        }),

                new Program("fatorialV2",
                        new Word[] {
                                new Word(Opcode.LDI, 0, -1, 5), // numero para colocar na memoria, ou pode ser lido
                                new Word(Opcode.STD, 0, -1, 19),
                                new Word(Opcode.LDD, 0, -1, 19),
                                new Word(Opcode.LDI, 1, -1, -1),
                                new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
                                new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
                                new Word(Opcode.LDI, 1, -1, 1),
                                new Word(Opcode.LDI, 6, -1, 1),
                                new Word(Opcode.LDI, 7, -1, 13),
                                new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula para STD (Stop-1)
                                new Word(Opcode.MULT, 1, 0, -1),
                                new Word(Opcode.SUB, 0, 6, -1),
                                new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
                                new Word(Opcode.STD, 1, -1, 18),
                                new Word(Opcode.LDI, 8, -1, 2), // escrita
                                new Word(Opcode.LDI, 9, -1, 18), // endereco com valor a escrever
                                new Word(Opcode.SYSCALL, -1, -1, -1),
                                new Word(Opcode.STOP, -1, -1, -1), // POS 17
                                new Word(Opcode.DATA, -1, -1, -1), // POS 18
                                new Word(Opcode.DATA, -1, -1, -1) } // POS 19
                ),

                new Program("progMinimo",
                        new Word[] {
                                new Word(Opcode.LDI, 0, -1, 999),
                                new Word(Opcode.STD, 0, -1, 8),
                                new Word(Opcode.STD, 0, -1, 9),
                                new Word(Opcode.STD, 0, -1, 10),
                                new Word(Opcode.STD, 0, -1, 11),
                                new Word(Opcode.STD, 0, -1, 12),
                                new Word(Opcode.STOP, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // 7
                                new Word(Opcode.DATA, -1, -1, -1), // 8
                                new Word(Opcode.DATA, -1, -1, -1), // 9
                                new Word(Opcode.DATA, -1, -1, -1), // 10
                                new Word(Opcode.DATA, -1, -1, -1), // 11
                                new Word(Opcode.DATA, -1, -1, -1), // 12
                                new Word(Opcode.DATA, -1, -1, -1) // 13
                        }),

                new Program("fibonacci10",
                        new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.STD, 1, -1, 20),
                                new Word(Opcode.LDI, 2, -1, 1),
                                new Word(Opcode.STD, 2, -1, 21),
                                new Word(Opcode.LDI, 0, -1, 22),
                                new Word(Opcode.LDI, 6, -1, 6),
                                new Word(Opcode.LDI, 7, -1, 31),
                                new Word(Opcode.LDI, 3, -1, 0),
                                new Word(Opcode.ADD, 3, 1, -1),
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.ADD, 1, 2, -1),
                                new Word(Opcode.ADD, 2, 3, -1),
                                new Word(Opcode.STX, 0, 2, -1),
                                new Word(Opcode.ADDI, 0, -1, 1),
                                new Word(Opcode.SUB, 7, 0, -1),
                                new Word(Opcode.JMPIG, 6, 7, -1),
                                new Word(Opcode.STOP, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // POS 20
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
                        }),

                new Program("fibonacci10v2",
                        new Word[] { // mesmo que prog exemplo, so que usa r0 no lugar de r8
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.STD, 1, -1, 20),
                                new Word(Opcode.LDI, 2, -1, 1),
                                new Word(Opcode.STD, 2, -1, 21),
                                new Word(Opcode.LDI, 0, -1, 22),
                                new Word(Opcode.LDI, 6, -1, 6),
                                new Word(Opcode.LDI, 7, -1, 31),
                                new Word(Opcode.MOVE, 3, 1, -1),
                                new Word(Opcode.MOVE, 1, 2, -1),
                                new Word(Opcode.ADD, 2, 3, -1),
                                new Word(Opcode.STX, 0, 2, -1),
                                new Word(Opcode.ADDI, 0, -1, 1),
                                new Word(Opcode.SUB, 7, 0, -1),
                                new Word(Opcode.JMPIG, 6, 7, -1),
                                new Word(Opcode.STOP, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // POS 20
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1) // ate aqui - serie de fibonacci ficara armazenada
                        }),
                new Program("fibonacciREAD",
                        new Word[] {
                                // mesmo que prog exemplo, so que usa r0 no lugar de r8
                                new Word(Opcode.LDI, 8, -1, 1), // leitura
                                new Word(Opcode.LDI, 9, -1, 55), // endereco a guardar o tamanho da serie de fib a gerar
                                                                  // - pode ser de 1 a 20
                                new Word(Opcode.SYSCALL, -1, -1, -1),
                                new Word(Opcode.LDD, 7, -1, 55),
                                new Word(Opcode.LDI, 3, -1, 0),
                                new Word(Opcode.ADD, 3, 7, -1),
                                new Word(Opcode.LDI, 4, -1, 36), // posicao para qual ira pular (stop) *
                                new Word(Opcode.LDI, 1, -1, -1), // caso negativo
                                new Word(Opcode.STD, 1, -1, 41),
                                new Word(Opcode.JMPIL, 4, 7, -1), // pula pra stop caso negativo *
                                new Word(Opcode.JMPIE, 4, 7, -1), // pula pra stop caso 0
                                new Word(Opcode.ADDI, 7, -1, 41), // fibonacci + posição do stop
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.STD, 1, -1, 41), // 25 posicao de memoria onde inicia a serie de
                                                                  // fibonacci gerada
                                new Word(Opcode.SUBI, 3, -1, 1), // se 1 pula pro stop
                                new Word(Opcode.JMPIE, 4, 3, -1),
                                new Word(Opcode.ADDI, 3, -1, 1),
                                new Word(Opcode.LDI, 2, -1, 1),
                                new Word(Opcode.STD, 2, -1, 42),
                                new Word(Opcode.SUBI, 3, -1, 2), // se 2 pula pro stop
                                new Word(Opcode.JMPIE, 4, 3, -1),
                                new Word(Opcode.LDI, 0, -1, 43),
                                new Word(Opcode.LDI, 6, -1, 25), // salva posição de retorno do loop
                                new Word(Opcode.LDI, 5, -1, 0), // salva tamanho
                                new Word(Opcode.ADD, 5, 7, -1),
                                new Word(Opcode.LDI, 7, -1, 0), // zera (inicio do loop)
                                new Word(Opcode.ADD, 7, 5, -1), // recarrega tamanho
                                new Word(Opcode.LDI, 3, -1, 0),
                                new Word(Opcode.ADD, 3, 1, -1),
                                new Word(Opcode.LDI, 1, -1, 0),
                                new Word(Opcode.ADD, 1, 2, -1),
                                new Word(Opcode.ADD, 2, 3, -1),
                                new Word(Opcode.STX, 0, 2, -1),
                                new Word(Opcode.ADDI, 0, -1, 1),
                                new Word(Opcode.SUB, 7, 0, -1),
                                new Word(Opcode.JMPIG, 6, 7, -1), // volta para o inicio do loop
                                new Word(Opcode.STOP, -1, -1, -1), // POS 36
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1), // POS 41
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1)
                        }),
                new Program("PB",
                        new Word[] {
                                // dado um inteiro em alguma posição de memória,
                                // se for negativo armazena -1 na saída; se for positivo responde o fatorial do
                                // número na saída
                                new Word(Opcode.LDI, 0, -1, 7), // numero para colocar na memoria
                                new Word(Opcode.STD, 0, -1, 50),
                                new Word(Opcode.LDD, 0, -1, 50),
                                new Word(Opcode.LDI, 1, -1, -1),
                                new Word(Opcode.LDI, 2, -1, 13), // SALVAR POS STOP
                                new Word(Opcode.JMPIL, 2, 0, -1), // caso negativo pula pro STD
                                new Word(Opcode.LDI, 1, -1, 1),
                                new Word(Opcode.LDI, 6, -1, 1),
                                new Word(Opcode.LDI, 7, -1, 13),
                                new Word(Opcode.JMPIE, 7, 0, 0), // POS 9 pula pra STD (Stop-1)
                                new Word(Opcode.MULT, 1, 0, -1),
                                new Word(Opcode.SUB, 0, 6, -1),
                                new Word(Opcode.JMP, -1, -1, 9), // pula para o JMPIE
                                new Word(Opcode.STD, 1, -1, 15),
                                new Word(Opcode.STOP, -1, -1, -1), // POS 14
                                new Word(Opcode.DATA, -1, -1, -1) // POS 15
                        }),
                new Program("PC",
                        new Word[] {
                                // Para um N definido (10 por exemplo)
                                // o programa ordena um vetor de N números em alguma posição de memória;
                                // ordena usando bubble sort
                                // loop ate que não swap nada
                                // passando pelos N valores
                                // faz swap de vizinhos se da esquerda maior que da direita
                                new Word(Opcode.LDI, 7, -1, 5), // TAMANHO DO BUBBLE SORT (N)
                                new Word(Opcode.LDI, 6, -1, 5), // aux N
                                new Word(Opcode.LDI, 5, -1, 46), // LOCAL DA MEMORIA
                                new Word(Opcode.LDI, 4, -1, 47), // aux local memoria
                                new Word(Opcode.LDI, 0, -1, 4), // colocando valores na memoria
                                new Word(Opcode.STD, 0, -1, 46),
                                new Word(Opcode.LDI, 0, -1, 3),
                                new Word(Opcode.STD, 0, -1, 47),
                                new Word(Opcode.LDI, 0, -1, 5),
                                new Word(Opcode.STD, 0, -1, 48),
                                new Word(Opcode.LDI, 0, -1, 1),
                                new Word(Opcode.STD, 0, -1, 49),
                                new Word(Opcode.LDI, 0, -1, 2),
                                new Word(Opcode.STD, 0, -1, 50), // colocando valores na memoria até aqui - POS 13
                                new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 1
                                new Word(Opcode.STD, 3, -1, 99),
                                new Word(Opcode.LDI, 3, -1, 22), // Posicao para pulo CHAVE 2
                                new Word(Opcode.STD, 3, -1, 98),
                                new Word(Opcode.LDI, 3, -1, 38), // Posicao para pulo CHAVE 3
                                new Word(Opcode.STD, 3, -1, 97),
                                new Word(Opcode.LDI, 3, -1, 25), // Posicao para pulo CHAVE 4 (não usada)
                                new Word(Opcode.STD, 3, -1, 96),
                                new Word(Opcode.LDI, 6, -1, 0), // r6 = r7 - 1 POS 22
                                new Word(Opcode.ADD, 6, 7, -1),
                                new Word(Opcode.SUBI, 6, -1, 1), // ate aqui
                                new Word(Opcode.JMPIEM, -1, 6, 97), // CHAVE 3 para pular quando r7 for 1 e r6 0 para
                                                                     // interomper o loop de vez do programa
                                new Word(Opcode.LDX, 0, 5, -1), // r0 e ra pegando valores das posições da memoria POS
                                                                 // 26
                                new Word(Opcode.LDX, 1, 4, -1),
                                new Word(Opcode.LDI, 2, -1, 0),
                                new Word(Opcode.ADD, 2, 0, -1),
                                new Word(Opcode.SUB, 2, 1, -1),
                                new Word(Opcode.ADDI, 4, -1, 1),
                                new Word(Opcode.SUBI, 6, -1, 1),
                                new Word(Opcode.JMPILM, -1, 2, 99), // LOOP chave 1 caso neg procura prox
                                new Word(Opcode.STX, 5, 1, -1),
                                new Word(Opcode.SUBI, 4, -1, 1),
                                new Word(Opcode.STX, 4, 0, -1),
                                new Word(Opcode.ADDI, 4, -1, 1),
                                new Word(Opcode.JMPIGM, -1, 6, 99), // LOOP chave 1 POS 38
                                new Word(Opcode.ADDI, 5, -1, 1),
                                new Word(Opcode.SUBI, 7, -1, 1),
                                new Word(Opcode.LDI, 4, -1, 0), // r4 = r5 + 1 POS 41
                                new Word(Opcode.ADD, 4, 5, -1),
                                new Word(Opcode.ADDI, 4, -1, 1), // ate aqui
                                new Word(Opcode.JMPIGM, -1, 7, 98), // LOOP chave 2
                                new Word(Opcode.STOP, -1, -1, -1), // POS 45
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1),
                                new Word(Opcode.DATA, -1, -1, -1)
                        })
        };
    }
}