error id: 41287B76D456D4CDB8851698D49BC21B
file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
### scala.reflect.internal.FatalError: 
  ThisType(value <local Cache>) for sym which is not a class
     while compiling: file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
        during phase: globalPhase=<no phase>, enteringPhase=parser
     library version: version 2.13.18
    compiler version: version 2.13.18
  reconstructed args: -deprecation -feature -Wconf:cat=feature:w -Wconf:cat=deprecation:ws -Wconf:cat=feature:ws -Wconf:cat=optimizer:ws -classpath <WORKSPACE>/.bloop/root/bloop-bsp-clients-classes/classes-Metals-rATmSHl1SyaWSCtnEbBwaQ==:<HOME>/Library/Caches/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.2/semanticdb-javac-0.11.2.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/7.7.0/chisel_2.13-7.7.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.10.7/os-lib_2.13-0.10.7.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native_2.13/4.1.0/json4s-native_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.7/data-class_2.13-0.2.7.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.3.1/upickle_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/2.0.1/firtool-resolver_2.13-2.0.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-core_2.13/4.1.0/json4s-core_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native-core_2.13/4.1.0/json4s-native-core_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.3.1/upack_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.3.1/upickle-implicits_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-ast_2.13/4.1.0/json4s-ast_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-scalap_2.13/4.1.0/json4s-scalap_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.jar -language:reflectiveCalls -Xcheckinit -Xplugin-require:semanticdb -Yrangepos -Ymacro-expand:discard -Ymacro-annotations -Ycache-plugin-class-loader:last-modified -Ypresentation-any-thread

  last tree to typer: TypeTree
       tree position: line 60 of file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
            tree tpe: <error>
              symbol: <none>
   symbol definition: <none> (a NoSymbol)
      symbol package: <none>
       symbol owners: 
           call site: <none> in <none>

== Source file context for tree position ==

    57         // -----------------------------------------------------------
    58         // 主状态机 (5个状态)
    59         val sIdle :: sLookup :: sMiss :: sReplace :: sRefill :: Nil = Enum(5)
    60         val main_state = RegInit(_CURSOR_sIdle)
    61 
    62         // Write Buffer 状态机 (2个状态)
    63         val wbIdle :: wbWrite :: Nil = Enum(2)

occurred in the presentation compiler.



action parameters:
offset: 2093
uri: file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
text:
```scala
package mycpu

import chisel3._
import chisel3.util._
import circt.stage.ChiselStage

// ==============================================================
// Cache 顶层模块 (RawModule 适配官方 TB)
// ==============================================================
class Cache extends RawModule {
    // 1. 显式定义时钟和低电平有效的复位
    val clk    = IO(Input(Clock()))
    val resetn = IO(Input(Bool()))

    // 2. 拍平的 CPU 交互接口
    val valid  = IO(Input(Bool()))
    val op     = IO(Input(Bool()))
    val index  = IO(Input(UInt(8.W)))
    val tag    = IO(Input(UInt(20.W)))
    val offset = IO(Input(UInt(4.W)))
    val wstrb  = IO(Input(UInt(4.W)))
    val wdata  = IO(Input(UInt(32.W)))

    val addr_ok = IO(Output(Bool()))
    val data_ok = IO(Output(Bool()))
    val rdata   = IO(Output(UInt(32.W)))

    // 3. 拍平的 AXI 转接桥交互接口
    val rd_req    = IO(Output(Bool()))
    val rd_type   = IO(Output(UInt(3.W)))
    val rd_addr   = IO(Output(UInt(32.W)))
    val rd_rdy    = IO(Input(Bool()))
    val ret_valid = IO(Input(Bool()))
    val ret_last  = IO(Input(Bool()))
    val ret_data  = IO(Input(UInt(32.W)))

    val wr_req    = IO(Output(Bool()))
    val wr_type   = IO(Output(UInt(3.W)))
    val wr_addr   = IO(Output(UInt(32.W)))
    val wr_wstrb  = IO(Output(UInt(4.W)))
    val wr_data   = IO(Output(UInt(128.W)))
    val wr_rdy    = IO(Input(Bool()))

    // ==============================================================
    // 核心逻辑区：必须包裹在时钟和复位域中 (注意 resetn 取反)
    // ==============================================================
    withClockAndReset(clk, !resetn) {
            
            // --- 你的所有内部代码都原封不动地放在这里面 ---
            // 例如：
            // val sIdle :: sLookup :: sMiss :: sReplace :: sRefill :: Nil = Enum(5)
            // val main_state = RegInit(sIdle)
            // ... (后面所有的逻辑)

        // -----------------------------------------------------------
        // 2.1 状态机定义
        // -----------------------------------------------------------
        // 主状态机 (5个状态)
        val sIdle :: sLookup :: sMiss :: sReplace :: sRefill :: Nil = Enum(5)
        val main_state = RegInit(@@sIdle)

        // Write Buffer 状态机 (2个状态)
        val wbIdle :: wbWrite :: Nil = Enum(2)
        val wb_state = RegInit(wbIdle)

        // -----------------------------------------------------------
        // 2.2 物理存储阵列 (12张表)
        // -----------------------------------------------------------
        // 1. TagV 表 (2块，单端口同步 RAM，256深度 x 21宽)
        val tagv_way0 = SyncReadMem(256, UInt(21.W))
        val tagv_way1 = SyncReadMem(256, UInt(21.W))

        // 2. Data Bank 表 (8块，单端口同步 RAM，支持字节写使能)
        // 使用 Vec(4, UInt(8.W)) 就可以让 Chisel 推断出带字节掩码的 BRAM！
        val data_way0_bank0 = SyncReadMem(256, Vec(4, UInt(8.W)))
        val data_way0_bank1 = SyncReadMem(256, Vec(4, UInt(8.W)))
        val data_way0_bank2 = SyncReadMem(256, Vec(4, UInt(8.W)))
        val data_way0_bank3 = SyncReadMem(256, Vec(4, UInt(8.W)))
        
        val data_way1_bank0 = SyncReadMem(256, Vec(4, UInt(8.W)))
        val data_way1_bank1 = SyncReadMem(256, Vec(4, UInt(8.W)))
        val data_way1_bank2 = SyncReadMem(256, Vec(4, UInt(8.W)))
        val data_way1_bank3 = SyncReadMem(256, Vec(4, UInt(8.W)))

        // 3. Dirty 表 (2块，用纯寄存器实现，读写互不干涉，且方便全部清零)
        val dirty_way0 = RegInit(VecInit(Seq.fill(256)(false.B)))
        val dirty_way1 = RegInit(VecInit(Seq.fill(256)(false.B)))

        // -----------------------------------------------------------
        // 2.3 核心外围缓冲 (Buffers)
        // -----------------------------------------------------------
        // 1. Request Buffer (保护案发现场)
        val req_op     = RegInit(false.B)
        val req_index  = RegInit(0.U(8.W))
        val req_tag    = RegInit(0.U(20.W))
        val req_offset = RegInit(0.U(4.W))
        val req_wstrb  = RegInit(0.U(4.W))
        val req_wdata  = RegInit(0.U(32.W))

        // 2. Miss Buffer (拆迁办后勤)
        val miss_replace_way = RegInit(false.B) // 0 踢 way0, 1 踢 way1
        val miss_ret_count   = RegInit(0.U(2.W)) // 记录 AXI 返回了几个字 (0~3)

        // 3. Write Buffer (延时写)
        val wb_way   = RegInit(false.B)
        val wb_index = RegInit(0.U(8.W))
        val wb_offset= RegInit(0.U(4.W))
        val wb_wstrb = RegInit(0.U(4.W))
        val wb_wdata = RegInit(0.U(32.W))

        // 4. LFSR 摇号机 (伪随机替换)
        val lfsr = RegInit("hACE1".U(16.W))
        lfsr := Cat(lfsr(14, 0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10)) // 简单的伽罗瓦LFSR
        val random_way = lfsr(0)

        // ==============================================================
        // 3. 核心控制逻辑与连线 (Step 2 新增)
        // ==============================================================
        
        // 3.1 SRAM 读地址控制 (地址给进去，下一拍出数据)
        // 如果是闲着或者刚来新指令，查 CPU 给的 index；如果是处理 Miss，查锁在 Request Buffer 里的 index
        val current_index = Mux(main_state === sIdle || main_state === sLookup, index, req_index)

        // 发起读取 (TagV 表)
        val tagv_rdata_way0 = tagv_way0.read(current_index)
        val tagv_rdata_way1 = tagv_way1.read(current_index)

        // 3.2 Tag 比较裁判 (在 Cycle 2 也就是 LOOKUP 状态拿到读出结果)
        val way0_v   = tagv_rdata_way0(0)
        val way0_tag = tagv_rdata_way0(20, 1)
        val way0_hit = way0_v && (way0_tag === req_tag) // 注意：是跟 req_tag (案发现场) 比！

        val way1_v   = tagv_rdata_way1(0)
        val way1_tag = tagv_rdata_way1(20, 1)
        val way1_hit = way1_v && (way1_tag === req_tag)

        val cache_hit = way0_hit || way1_hit

        // 3.3 Request Buffer 快门逻辑 (记录案发现场)
        
        // 3.3.1 真实的写后读冲突检测 (Hazard)
        val hazard_lookup_write = (main_state === sLookup) && (req_op === true.B)
        // 情况1：主状态机在 LOOKUP 阶段，且当前指令是命中写 (Store Hit)
        val real_write_hit = (main_state === sLookup) && cache_hit && (req_op === true.B)
        
        // 情况2：Write Buffer 状态机在 WRITE 阶段，正在努力写 SRAM
        val wb_is_writing = (wb_state === wbWrite)
        
        // 新来的请求是 Load 读操作
        val new_is_read = valid && (op === false.B)

        // 新来的请求不管读写，只要地址冲突了就得拦下来！
        val new_req_valid = valid
        // 冲突条件：新读请求的 Index 和 字内偏移(Offset的高2位) 与正在写的地址完全重叠！
        // (因为同一个字正在被改写，不能马上读，必须阻塞 Stall)
        val hazard_with_lookup = hazard_lookup_write && (index === req_index) && (offset(3,2) === req_offset(3,2))
        val hazard_with_wb     = wb_is_writing && (index === wb_index) && (offset(3,2) === wb_offset(3,2))
        
        val hit_write_hazard = new_req_valid && (hazard_with_lookup || hazard_with_wb)



        // 什么时候吸纳新指令？ (也就是 addr_ok 什么时候拉高)
        val can_accept_new = (main_state === sIdle) || 
                            (main_state === sLookup && cache_hit && (!op || (op && !hit_write_hazard)))

        addr_ok := can_accept_new

        // 按下快门！只要 addr_ok 拉高且有 valid 请求，立刻把 CPU 的输入锁进 Request Buffer
        when(valid && addr_ok) {
            req_op     := op
            req_index  := index
            req_tag    := tag
            req_offset := offset
            req_wstrb  := wstrb
            req_wdata  := wdata
        }
        // 3.4 放行与握手 (data_ok)
        // 满足以下任一条件，告诉 CPU 这条指令干完了：
        // 1. LOOKUP 阶段，并且命中了 (不管是 Load 还是 Store)
        // 2. LOOKUP 阶段，并且这是一个 Store 操作 (因为哪怕 Miss，Store 也可以扔给后面慢慢搞，CPU 先走)
        // 【书本 10.1.3.4 高级优化】：REFILL 阶段，AXI 返回有效数据，且返回的字号正好等于 CPU 请求的字号！
        val refill_bypass_match = (main_state === sRefill) && ret_valid && (miss_ret_count === req_offset(3,2))

        data_ok := (main_state === sLookup && cache_hit) || 
                        (main_state === sLookup && req_op === true.B) ||
                        refill_bypass_match // 命中优化条件，提前一拍让 CPU 解除阻塞！

        // ==============================================================
        // 4. 双状态机流转逻辑 (Step 3 新增)
        // ==============================================================

        // 4.1 主状态机 (5个状态)
        switch(main_state) {
            is(sIdle) {
                // 闲置时，有请求且没冲突，直接进入查表
                when(valid && !hit_write_hazard) {
                    main_state := sLookup
                }
            }
            is(sLookup) {
                when(cache_hit) {
                    // 命中了！如果有新请求且无冲突，流水线狂奔不停；否则回去闲置
                    when(valid && !hit_write_hazard) {
                        main_state := sLookup 
                    } .otherwise {
                        main_state := sIdle
                    }
                } .otherwise {
                    // 没命中，漏了！进入漫长的补救流程
                    main_state := sMiss       
                }
            }
            is(sMiss) {
                // 等待 AXI 说：“我的写通道准备好了”
                when(wr_rdy) {
                    main_state := sReplace    
                    miss_replace_way := random_way // 摇号机决定踢哪一路！存进 Miss Buffer
                }
            }
            is(sReplace) {
                // 等待 AXI 说：“我的读通道准备好了”
                when(rd_rdy) {
                    main_state := sRefill     
                    miss_ret_count := 0.U     // 计数器清零，准备接收 4 个字的快递
                }
            }
            is(sRefill) {
                // 每次 AXI 吐回一个有效数据，计数器 +1
                when(ret_valid) {
                    miss_ret_count := miss_ret_count + 1.U
                    // 如果这是最后一个数据 (16字节传完了)
                    when(ret_last) {
                        main_state := sIdle   // 功德圆满，回家接客！
                    }
                }
            }
        }

        // 4.2 Write Buffer 状态机 (给命中写擦屁股的后台任务)
        switch(wb_state) {
            is(wbIdle) {
                // 只要主状态机发现命中写，立刻启动后台写任务
                when(real_write_hit) { 
                    wb_state := wbWrite
                    wb_way   := Mux(way1_hit, true.B, false.B) // 记下命中的是哪一路
                    wb_index := req_index
                    wb_offset:= req_offset
                    wb_wstrb := req_wstrb
                    wb_wdata := req_wdata
                }
            }
            is(wbWrite) {
                // 如果连续不断地发生命中写，就一直在 WRITE 状态加班
                when(real_write_hit) { 
                    wb_state := wbWrite
                    wb_way   := Mux(way1_hit, true.B, false.B)
                    wb_index := req_index
                    wb_offset:= req_offset
                    wb_wstrb := req_wstrb
                    wb_wdata := req_wdata
                } .otherwise {
                    wb_state := wbIdle // 活干完了，回去闲置
                }
            }
        }
        // ==============================================================
        // 5. 终极数据连线 (Step 4 新增)
        // ==============================================================

        // 5.1 统一读取 Data Bank 与数据选择
        // 警告：Chisel 中对同一个 SyncReadMem 只能调用一次 .read()，否则会生成多端口 RAM 导致 FPGA 报错！
        val rdata_w0_b0 = data_way0_bank0.read(current_index)
        val rdata_w0_b1 = data_way0_bank1.read(current_index)
        val rdata_w0_b2 = data_way0_bank2.read(current_index)
        val rdata_w0_b3 = data_way0_bank3.read(current_index)

        val rdata_w1_b0 = data_way1_bank0.read(current_index)
        val rdata_w1_b1 = data_way1_bank1.read(current_index)
        val rdata_w1_b2 = data_way1_bank2.read(current_index)
        val rdata_w1_b3 = data_way1_bank3.read(current_index)

        // 给 CPU 的 32 位数据 (根据 offset 挑选 Bank)
        val way0_word = MuxLookup(req_offset(3,2), 0.U)(Seq(
            0.U -> rdata_w0_b0.asUInt,
            1.U -> rdata_w0_b1.asUInt,
            2.U -> rdata_w0_b2.asUInt,
            3.U -> rdata_w0_b3.asUInt
        ))
        val way1_word = MuxLookup(req_offset(3,2), 0.U)(Seq(
            0.U -> rdata_w1_b0.asUInt,
            1.U -> rdata_w1_b1.asUInt,
            2.U -> rdata_w1_b2.asUInt,
            3.U -> rdata_w1_b3.asUInt
        ))

        // 命中了哪路，就把哪路的数据给 CPU
        rdata := Mux(refill_bypass_match, ret_data, Mux(way0_hit, way0_word, way1_word))
        // （高级优化：如果是 REFILL 阶段且 ret_valid，这里还可以直接 Bypass AXI 的返回数据 ret_data）

        // 5.2 AXI 读请求 (向主存要数据)
        rd_req  := (main_state === sReplace) // 只有在 REPLACE 状态才拉高读请求
        rd_type := 4.U // 3 代表 Burst 传输 16 字节
        rd_addr := Cat(req_tag, req_index, 0.U(4.W)) // 缺失的物理地址，末 4 位清零对齐

        // 5.3 AXI 写请求 (踢掉脏数据) - 【修复：只写回 V=1 且 D=1 的行】
        // 提取 Victim（被替换行）的 V 位和 D 位 (此时 current_index 已经是 req_index)
        val victim_v = Mux(miss_replace_way === 0.U, way0_v, way1_v)
        val victim_d = Mux(miss_replace_way === 0.U, dirty_way0(req_index), dirty_way1(req_index))
        val need_writeback = victim_v && victim_d // 只有既有效又脏，才需要写回！

        // 5.3 AXI 写请求 (踢掉脏数据)
        val axi_wr_req_reg = RegInit(false.B)
        val miss_to_replace = (main_state === sMiss) && wr_rdy
        // 书本规范：MISS -> REPLACE 状态转换发生条件将其置 1。随后 wr_rdy 为 1 将其从 1 清为 0。
        when(miss_to_replace && need_writeback) {
            axi_wr_req_reg := true.B
        } .elsewhen(axi_wr_req_reg && wr_rdy) {
            axi_wr_req_reg := false.B
        }
        
        // 如果不需要写回，wr_req 永远是 false，AXI 转接桥会直接忽略写操作
        wr_req  := axi_wr_req_reg
        wr_type := 4.U
        // 拼接 128 位的写回数据 (这里用到了你在 REPLACE 阶段读出的那一路的所有 Bank 数据)
        // 物理地址：被踢掉的那个行的原 Tag + Index + 0000
        val replace_tag = Mux(miss_replace_way === 0.U, way0_tag, way1_tag)
        wr_addr  := Cat(replace_tag, req_index, 0.U(4.W))
        wr_wstrb := "hffff".U 
        // 这里需要将选中的那一路的 4 个 Bank 拼接成 128 位
        // 拼接 128 位的写回数据 (Cat 是高位在左，低位在右，所以从 bank3 拼到 bank0)
        val way0_line = Cat(rdata_w0_b3.asUInt, rdata_w0_b2.asUInt, rdata_w0_b1.asUInt, rdata_w0_b0.asUInt)
        val way1_line = Cat(rdata_w1_b3.asUInt, rdata_w1_b2.asUInt, rdata_w1_b1.asUInt, rdata_w1_b0.asUInt)
        wr_data := Mux(miss_replace_way === 0.U, way0_line, way1_line)

        // 5.4 SRAM 写使能控制 (完全翻译书本 表 10.5)
        
        // TagV 表写使能：只有在 REFILL 阶段，且 AXI 传回了最后一次数据 (ret_last) 时才写
        val tagv_we = (main_state === sRefill) && ret_valid && ret_last
        when(tagv_we) {
            val new_tagv = Cat(req_tag, 1.U(1.W)) // Tag + V位(1)
            when(miss_replace_way === 0.U) { tagv_way0.write(req_index, new_tagv) }
            .otherwise                     { tagv_way1.write(req_index, new_tagv) }
        }

        // Dirty 表写使能：
        // 情况1：命中写 (Hit Write)，把它变脏 (1)
        // 情况2：Refill 完成，根据当前指令是不是 Store 决定它是脏 (1) 还是干净 (0)
        when(wb_state === wbWrite) {
            when(wb_way === 0.U) { dirty_way0(wb_index) := true.B }
            .otherwise           { dirty_way1(wb_index) := true.B }
        }
        when(tagv_we) { // 复用 REFILL 结束的条件
            when(miss_replace_way === 0.U) { dirty_way0(req_index) := req_op }
            .otherwise                     { dirty_way1(req_index) := req_op }
        }

        // 5.4 终极 Data Bank 写入路由 (解决多端口报错 & Store Miss 丢数据)
        
        // 解析 AXI 返回的数据和 Write Buffer 的数据
        val axi_data_vec = VecInit(ret_data(7,0), ret_data(15,8), ret_data(23,16), ret_data(31,24))
        val wb_data_vec  = VecInit(wb_wdata(7,0), wb_wdata(15,8), wb_wdata(23,16), wb_wdata(31,24))
        val req_data_vec = VecInit(req_wdata(7,0), req_wdata(15,8), req_wdata(23,16), req_wdata(31,24)) // <--- 新增这行

        // 各种写状态的判断
        val is_refill = (main_state === sRefill) && ret_valid
        val is_hit_write = (wb_state === wbWrite)
        
        // 【修复 Store Miss 丢数据】：如果正在 Refill，且当前是 Store 指令，且 AXI 返回的字刚好是我们要写的那个字！
        val is_target_word = (miss_ret_count === req_offset(3,2))
        val store_miss_merge = is_refill && (req_op === true.B) && is_target_word

        // 为 8 个 Bank 准备统一的控制线
        for (way <- 0 until 2) {
            for (bank <- 0 until 4) {
                val we   = WireDefault(false.B)
                val addr = WireDefault(0.U(8.W))
                val data = WireDefault(VecInit(Seq.fill(4)(0.U(8.W))))
                val mask = WireDefault(VecInit(Seq.fill(4)(false.B)))

                // 情况 1: Hit Write 写入
                when(is_hit_write && (wb_way === way.U) && (wb_offset(3,2) === bank.U)) {
                    we   := true.B
                    addr := wb_index
                    data := wb_data_vec
                    mask := VecInit(wb_wstrb(0), wb_wstrb(1), wb_wstrb(2), wb_wstrb(3))
                }
                // 情况 2: Refill 写入
                .elsewhen(is_refill && (miss_replace_way === way.U) && (miss_ret_count === bank.U)) {
                    we   := true.B
                    addr := req_index
                    
                    // 【核心合并逻辑】：如果是 Store Miss，并且到了目标字，用 wstrb 掩码决定是用 req_wdata 还是 AXI 的数据
                    for (i <- 0 until 4) {
                        when(store_miss_merge && req_wstrb(i)) {
                            data(i) := req_data_vec(i) // 【修复致命 Bug】：用 Request Buffer 里真正要写的数据，绝不能用 wb_data_vec！
                        } .otherwise {
                            data(i) := axi_data_vec(i) // 否则老老实实用主存的数据
                        }
                        mask(i) := true.B // Refill 期间 4 个字节全写
                    }
                }

                // 统一调用唯一的一次 .write()
                val target_ram = (way, bank) match {
                    case (0, 0) => data_way0_bank0
                    case (0, 1) => data_way0_bank1
                    case (0, 2) => data_way0_bank2
                    case (0, 3) => data_way0_bank3
                    case (1, 0) => data_way1_bank0
                    case (1, 1) => data_way1_bank1
                    case (1, 2) => data_way1_bank2
                    case (1, 3) => data_way1_bank3
                }
                when(we) {
                    target_ram.write(addr, data, mask)
                }
            }
        }
    }
}

object Elaborate extends App {
    ChiselStage.emitSystemVerilogFile(
        new Cache(), // 实例化你的 Cache 模块
        firtoolOpts = Array(
            "-disable-all-randomization", 
            "-strip-debug-info"
        )
    )
}
```


presentation compiler configuration:
Scala version: 2.13.18
Classpath:
<WORKSPACE>/.bloop/root/bloop-bsp-clients-classes/classes-Metals-rATmSHl1SyaWSCtnEbBwaQ== [exists ], <HOME>/Library/Caches/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.2/semanticdb-javac-0.11.2.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/7.7.0/chisel_2.13-7.7.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.10.7/os-lib_2.13-0.10.7.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native_2.13/4.1.0/json4s-native_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.7/data-class_2.13-0.2.7.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.3.1/upickle_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/2.0.1/firtool-resolver_2.13-2.0.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-core_2.13/4.1.0/json4s-core_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native-core_2.13/4.1.0/json4s-native-core_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.3.1/upack_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.3.1/upickle-implicits_2.13-3.3.1.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-ast_2.13/4.1.0/json4s-ast_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-scalap_2.13/4.1.0/json4s-scalap_2.13-4.1.0.jar [exists ], <HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.jar [exists ]
Options:
-language:reflectiveCalls -deprecation -feature -Xcheckinit -Ymacro-annotations -Yrangepos -Xplugin-require:semanticdb




#### Error stacktrace:

```
scala.reflect.internal.Reporting.abort(Reporting.scala:70)
	scala.reflect.internal.Reporting.abort$(Reporting.scala:66)
	scala.reflect.internal.SymbolTable.abort(SymbolTable.scala:28)
	scala.reflect.internal.Types$ThisType.<init>(Types.scala:1389)
	scala.reflect.internal.Types$UniqueThisType.<init>(Types.scala:1409)
	scala.reflect.internal.Types$ThisType$.apply(Types.scala:1413)
	scala.meta.internal.pc.AutoImportsProvider$$anonfun$1.applyOrElse(AutoImportsProvider.scala:108)
	scala.meta.internal.pc.AutoImportsProvider$$anonfun$1.applyOrElse(AutoImportsProvider.scala:90)
	scala.collection.immutable.List.collect(List.scala:257)
	scala.meta.internal.pc.AutoImportsProvider.autoImports(AutoImportsProvider.scala:90)
	scala.meta.internal.pc.ScalaPresentationCompiler.$anonfun$autoImports$1(ScalaPresentationCompiler.scala:399)
	scala.meta.internal.pc.CompilerAccess.retryWithCleanCompiler(CompilerAccess.scala:182)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withSharedCompiler$1(CompilerAccess.scala:155)
	scala.Option.map(Option.scala:242)
	scala.meta.internal.pc.CompilerAccess.withSharedCompiler(CompilerAccess.scala:154)
	scala.meta.internal.pc.CompilerAccess.$anonfun$withInterruptableCompiler$1(CompilerAccess.scala:92)
	scala.meta.internal.pc.CompilerAccess.$anonfun$onCompilerJobQueue$1(CompilerAccess.scala:209)
	scala.meta.internal.pc.CompilerJobQueue$Job.run(CompilerJobQueue.scala:152)
	java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
	java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	java.base/java.lang.Thread.run(Thread.java:840)
```
#### Short summary: 

scala.reflect.internal.FatalError: 
  ThisType(value <local Cache>) for sym which is not a class
     while compiling: file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
        during phase: globalPhase=<no phase>, enteringPhase=parser
     library version: version 2.13.18
    compiler version: version 2.13.18
  reconstructed args: -deprecation -feature -Wconf:cat=feature:w -Wconf:cat=deprecation:ws -Wconf:cat=feature:ws -Wconf:cat=optimizer:ws -classpath <WORKSPACE>/.bloop/root/bloop-bsp-clients-classes/classes-Metals-rATmSHl1SyaWSCtnEbBwaQ==:<HOME>/Library/Caches/bloop/semanticdb/com.sourcegraph.semanticdb-javac.0.11.2/semanticdb-javac-0.11.2.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.13.18/scala-library-2.13.18.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/chisel_2.13/7.7.0/chisel_2.13-7.7.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/github/scopt/scopt_2.13/4.1.0/scopt_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/os-lib_2.13/0.10.7/os-lib_2.13-0.10.7.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native_2.13/4.1.0/json4s-native_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/alexarchambault/data-class_2.13/0.2.7/data-class_2.13-0.2.7.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.13.18/scala-reflect-2.13.18.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle_2.13/3.3.1/upickle_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/chipsalliance/firtool-resolver_2.13/2.0.1/firtool-resolver_2.13-2.0.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/geny_2.13/1.1.1/geny_2.13-1.1.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-core_2.13/4.1.0/json4s-core_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-native-core_2.13/4.1.0/json4s-native-core_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/ujson_2.13/3.3.1/ujson_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upack_2.13/3.3.1/upack_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-implicits_2.13/3.3.1/upickle-implicits_2.13-3.3.1.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.13/2.2.0/scala-xml_2.13-2.2.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-collection-compat_2.13/2.11.0/scala-collection-compat_2.13-2.11.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-ast_2.13/4.1.0/json4s-ast_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/io/github/json4s/json4s-scalap_2.13/4.1.0/json4s-scalap_2.13-4.1.0.jar:<HOME>/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/com/lihaoyi/upickle-core_2.13/3.3.1/upickle-core_2.13-3.3.1.jar -language:reflectiveCalls -Xcheckinit -Xplugin-require:semanticdb -Yrangepos -Ymacro-expand:discard -Ymacro-annotations -Ycache-plugin-class-loader:last-modified -Ypresentation-any-thread

  last tree to typer: TypeTree
       tree position: line 60 of file://<WORKSPACE>/src/main/scala/mycpu/Cache.scala
            tree tpe: <error>
              symbol: <none>
   symbol definition: <none> (a NoSymbol)
      symbol package: <none>
       symbol owners: 
           call site: <none> in <none>

== Source file context for tree position ==

    57         // -----------------------------------------------------------
    58         // 主状态机 (5个状态)
    59         val sIdle :: sLookup :: sMiss :: sReplace :: sRefill :: Nil = Enum(5)
    60         val main_state = RegInit(_CURSOR_sIdle)
    61 
    62         // Write Buffer 状态机 (2个状态)
    63         val wbIdle :: wbWrite :: Nil = Enum(2)